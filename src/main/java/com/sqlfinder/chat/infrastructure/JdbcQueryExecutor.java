package com.sqlfinder.chat.infrastructure;

import com.sqlfinder.chat.application.QueryExecutionException;
import com.sqlfinder.chat.application.RequestExecution;
import com.sqlfinder.chat.application.QueryExecutor;
import com.sqlfinder.chat.application.SqlValidator;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import com.sqlfinder.chat.model.QueryResult;
import com.sqlfinder.chat.model.ResultColumn;
import com.sqlfinder.chat.model.ValidatedSql;
import com.sqlfinder.datasource.application.UserMetadataService;
import com.sqlfinder.auth.application.TableAccessDeniedException;
import com.sqlfinder.datasource.config.MetadataProperties;
import com.sqlfinder.datasource.model.TableSchema;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 只读查询执行器 */
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
        checkExecution();
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
            checkExecution();
            throw new QueryExecutionException("QUERY_METADATA_FAILED", "读取授权表结构失败。", e);
        }
        // 使用服务端授权结构重新校验，不信任传入摘要。
        ValidatedSql checked = sqlValidator.validate(candidate.sql(), schemas);
        checkExecution();
        String catalog = schemas.get(0).catalog();
        if (!catalog.equals(metadataProperties.getCatalog())) {
            throw new QueryExecutionException("QUERY_CATALOG_MISMATCH", "授权数据库与配置不一致。");
        }

        try (Connection connection = dataSource.getConnection()) {
            checkExecution();
            if (!catalog.equals(connection.getCatalog())) {
                throw new QueryExecutionException("QUERY_CATALOG_MISMATCH", "查询连接的数据库与授权范围不一致。");
            }
            return executeReadOnly(connection, checked.sql());
        } catch (SQLTimeoutException e) {
            checkExecution();
            throw new QueryExecutionException("QUERY_TIMEOUT", "查询超时，请缩小查询范围。", e);
        } catch (SQLException e) {
            checkExecution();
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
            RequestExecution execution = RequestExecution.current();
            AutoCloseable registration = execution == null ? () -> {} : execution.onCancel(() -> {
                try { statement.cancel(); } catch (SQLException ignored) { }
            });
            try {
                checkExecution();
                int timeout = properties.timeoutSeconds();
                if (execution != null) timeout = (int) Math.min(timeout,
                        Math.max(1, (execution.remainingMillis() + 999) / 1000));
                statement.setQueryTimeout(timeout);
                statement.setMaxRows(properties.maxRows() + 1);
                checkExecution();
                try (ResultSet rs = statement.executeQuery()) {
                    checkExecution();
                    result = readResult(rs);
                }
                checkExecution();
            } finally {
                try { registration.close(); } catch (Exception ignored) { }
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
        List<ResultColumn> columnMetadata = new ArrayList<>();
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
            columnMetadata.add(ResultColumn.from(metadata,i,label));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        int characters = 0;
        while (true) {
            checkExecution();
            if (!rs.next()) break;
            checkExecution();
            if (rows.size() == properties.maxRows()) {
                truncated = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= count; i++) {
                checkExecution();
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
        return new QueryResult(List.copyOf(columns), List.copyOf(rows), truncated,List.copyOf(columnMetadata));
    }

    private Object readValue(ResultSet rs, int index) throws SQLException {
        Object value = rs.getObject(index);
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof java.math.BigDecimal decimal) {
            // Bound before toPlainString: a huge exponent must not allocate an unbounded string.
            if ((long)decimal.precision()+Math.abs((long)decimal.scale())+3>MAX_CELL_SIZE)
                throw new QueryExecutionException("QUERY_RESULT_TOO_LARGE","单个数值结果过大。");
            return boundedText(decimal.toPlainString());
        }
        if (value instanceof java.math.BigInteger integer) return boundedText(integer.toString());
        if (value instanceof Long number && (number>9_007_199_254_740_991L || number< -9_007_199_254_740_991L))
            return number.toString();
        if (value instanceof Number number) {
            if ((number instanceof Double d && !Double.isFinite(d))
                    || (number instanceof Float f && !Float.isFinite(f))) {
                throw new QueryExecutionException("QUERY_UNSUPPORTED_VALUE", "结果包含无法表示为 JSON 的数值。");
            }
            if (number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long
                    || number instanceof Float || number instanceof Double) return number;
            throw new QueryExecutionException("QUERY_UNSUPPORTED_VALUE","当前不支持此数值结果类型。");
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
        if (value instanceof java.sql.Timestamp timestamp) return boundedText(timestamp.toLocalDateTime().toString());
        if (value instanceof java.sql.Date || value instanceof java.sql.Time
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

    private void checkExecution() {
        RequestExecution execution = RequestExecution.current();
        if (execution != null) execution.check();
    }
}
