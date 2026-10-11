package com.sqlfinder.datasource.infrastructure;

import com.sqlfinder.datasource.application.MetadataService;
import com.sqlfinder.datasource.config.MetadataProperties;
import com.sqlfinder.datasource.model.ColumnSchema;
import com.sqlfinder.datasource.model.TableSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
public class JdbcMetadataService implements MetadataService {

    private final DataSource dataSource;
    private final MetadataProperties properties;

    @Autowired
    public JdbcMetadataService(DataSource dataSource, MetadataProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @Override
    public List<TableSchema> getAuthorizedSchemas() {
        String catalog = properties.getCatalog();
        List<String> allowedTables = properties.getAllowedTables();

        if (catalog == null || catalog.isBlank()) {
            throw new IllegalStateException("Catalog required");
        }
        if (allowedTables == null || allowedTables.isEmpty()) {
            throw new IllegalStateException("Tables required");
        }

        List<TableSchema> tables = new ArrayList<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            for (String tableName : allowedTables) {
                if (tableName == null || tableName.isBlank()) {
                    throw new IllegalStateException("Table name required");
                }

                List<ColumnSchema> columns =
                        readColumns(metaData, catalog, tableName);

                if (columns.isEmpty()) {
                    throw new IllegalStateException("No columns found: " + tableName);
                }

                tables.add(new TableSchema(
                        catalog,
                        tableName,
                        "",
                        List.copyOf(columns)
                ));
            }

            return List.copyOf(tables);
        } catch (SQLException e) {
            throw new IllegalStateException("读取数据库元数据失败", e);
        }
    }

    private List<ColumnSchema> readColumns(
            DatabaseMetaData metadata,
            String catalog,
            String tableName) throws SQLException {

        String escape = metadata.getSearchStringEscape();
        String tablePattern = tableName
                .replace(escape, escape + escape)
                .replace("_", escape + "_")
                .replace("%", escape + "%");
        // 上面这块是匹配规则处理，主要处理下划线的问题

        List<ColumnSchema> columns = new ArrayList<>();

        try (ResultSet rs = metadata.getColumns(catalog, null, tablePattern, "%")) {

            while (rs.next()) {
                if (!tableName.equals(rs.getString("TABLE_NAME"))) {
                    continue;
                }

                columns.add(new ColumnSchema(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("NULLABLE")
                                == DatabaseMetaData.columnNullable,
                        rs.getString("REMARKS")
                ));
            }
        }

        return columns;
    }
}
