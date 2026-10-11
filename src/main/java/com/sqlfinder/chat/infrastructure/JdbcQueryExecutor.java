package com.sqlfinder.chat.infrastructure;

import com.sqlfinder.chat.application.QueryExecutionException;
import com.sqlfinder.chat.application.QueryExecutor;
import com.sqlfinder.chat.application.SqlValidator;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import com.sqlfinder.chat.model.QueryResult;
import com.sqlfinder.chat.model.ValidatedSql;
import com.sqlfinder.datasource.application.UserMetadataService;
import com.sqlfinder.auth.application.TableAccessDeniedException;
import com.sqlfinder.datasource.config.MetadataProperties;
import com.sqlfinder.datasource.model.TableSchema;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * JDBC查询执行器
 */
@Service
public class JdbcQueryExecutor implements QueryExecutor {
    private static final int MAX_COLUMNS = 100;
    private static final int MAX_CELL_SIZE = 16_384;
    private static final int MAX_RESULT_CHARACTERS = 1_000_000;

    private final DataSource dataSource;
    private final UserMetadataService metadataService;
    private final MetadataProperties metadataProperties;
    private final SqlValidator sqlValidator;
    private final QueryExecutionProperties properties;

    public JdbcQueryExecutor(
            DataSource dataSource,
            UserMetadataService metadataService,
            MetadataProperties metadataProperties,
            SqlValidator sqlValidator,
            QueryExecutionProperties properties) {
        this.dataSource = dataSource;
        this.metadataService = metadataService;
        this.metadataProperties = metadataProperties;
        this.sqlValidator = sqlValidator;
        this.properties = properties;
    }

    @Override
    public QueryResult execute(String userId, ValidatedSql candidate) {
        if (!properties.enabled()) {
            throw new QueryExecutionException("QUERY_DISABLED", "查询执行尚未开启。");
        }
        if (candidate == null) {
            throw new QueryExecutionException("QUERY_INVALID_INPUT", "待执行查询不能为空。");
        }
        List<TableSchema> schemas;
        try {
            schemas = metadataService.getAuthorizedSchemas(userId);
        } catch (TableAccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new QueryExecutionException("QUERY_METADATA_FAILED", "读取授权表结构失败。", e);
        }
        // 使用服务端授权结构重新校验，不信任传入摘要。
        ValidatedSql checked = sqlValidator.validate(candidate.sql(), schemas);
        String catalog = schemas.get(0).catalog();
        if (!catalog.equals(metadataProperties.getCatalog())) {
            throw new QueryExecutionException("QUERY_CATALOG_MISMATCH", "授权数据库与配置不一致。");
        }

        try (Connection connection = dataSource.getConnection()) {
            if (!catalog.equals(connection.getCatalog())) {
                throw new QueryExecutionException("QUERY_CATALOG_MISMATCH", "查询连接的数据库与授权范围不一致。");
            }
            return executeReadOnly(connection, checked.sql());
        } catch (SQLTimeoutException e) {
            throw new QueryExecutionException("QUERY_TIMEOUT", "查询超时，请缩小查询范围。", e);
        } catch (SQLException e) {
            throw new QueryExecutionException("QUERY_FAILED", "数据库查询失败。", e);
        }
    }

    private QueryResult executeReadOnly(Connection connection, String sql) throws SQLException {
        // 先设置只读，再关闭自动提交；本执行器拥有并关闭此连接。
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        QueryResult result;
        try (PreparedStatement statement = connection.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setQueryTimeout(properties.timeoutSeconds());
            statement.setMaxRows(properties.maxRows() + 1);
            try (ResultSet rs = statement.executeQuery()) {
                result = readResult(rs);
            }
        } catch (SQLException | RuntimeException e) {
            // 回滚失败作为附加异常保留，避免覆盖最初的错误。
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        }
        connection.rollback();
        return result;
    }

    private QueryResult readResult(ResultSet rs) throws SQLException {
        ResultSetMetaData metadata = rs.getMetaData();
        int count = metadata.getColumnCount();
        if (count < 1 || count > MAX_COLUMNS) {
            throw new QueryExecutionException("QUERY_RESULT_TOO_LARGE", "返回列数超出允许范围。");
        }
        List<String> columns = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (int i = 1; i <= count; i++) {
            String label = metadata.getColumnLabel(i);
            if (label == null || label.isBlank()) {
                throw new QueryExecutionException("QUERY_INVALID_COLUMN", "查询结果缺少列名，请使用 AS 指定别名。");
            }
            if (!labels.add(label)) {
                throw new QueryExecutionException("QUERY_DUPLICATE_COLUMN", "返回列名重复，请使用不同的 AS 别名。");
            }
            columns.add(label);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        int characters = 0;
        while (rs.next()) {
            if (rows.size() == properties.maxRows()) {
                truncated = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= count; i++) {
                Object value = readValue(rs, i);
                // 包括列名的重复存储；这是返回内容预算，并非精确的内存或 JSON 字节数。
                characters += columns.get(i - 1).length()
                        + (value == null ? 4 : value.toString().length());
                if (characters > MAX_RESULT_CHARACTERS) {
                    throw new QueryExecutionException("QUERY_RESULT_TOO_LARGE", "返回内容过大，请缩小查询范围。");
                }
                row.put(columns.get(i - 1), value);
            }
            // unmodifiableMap 允许 SQL NULL；Map.copyOf 不允许 null。
            rows.add(Collections.unmodifiableMap(row));
        }
        return new QueryResult(List.copyOf(columns), List.copyOf(rows), truncated);
    }

    private Object readValue(ResultSet rs, int index) throws SQLException {
        Object value = rs.getObject(index);
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            if ((number instanceof Double d && !Double.isFinite(d))
                    || (number instanceof Float f && !Float.isFinite(f))) {
                throw new QueryExecutionException("QUERY_UNSUPPORTED_VALUE", "结果包含无法表示为 JSON 的数值。");
            }
            return number;
        }
        if (value instanceof byte[] bytes) {
            if (bytes.length > MAX_CELL_SIZE) {
                throw new QueryExecutionException("QUERY_RESULT_TOO_LARGE", "单个二进制结果过大。");
            }
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof String text) {
            return boundedText(text);
        }
        if (value instanceof java.sql.Date || value instanceof java.sql.Time
                || value instanceof java.sql.Timestamp
                || value instanceof java.time.temporal.TemporalAccessor) {
            return boundedText(value.toString());
        }
        // 不直接序列化驱动的 Blob、Clob 或其他专有对象。
        throw new QueryExecutionException("QUERY_UNSUPPORTED_VALUE", "当前不支持此查询结果类型。");
    }

    private String boundedText(String text) {
        if (text.length() > MAX_CELL_SIZE) {
            throw new QueryExecutionException("QUERY_RESULT_TOO_LARGE", "单个文本结果过大。");
        }
        return text;
    }
}
