package com.sqlfinder.chat.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.auth.config.AuthProperties;
import com.sqlfinder.chat.application.SqlValidationException;
import com.sqlfinder.chat.application.SqlValidator;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import com.sqlfinder.chat.infrastructure.JdbcQueryExecutor;
import com.sqlfinder.datasource.application.UserMetadataService;
import com.sqlfinder.datasource.config.MetadataProperties;
import com.sqlfinder.datasource.infrastructure.JdbcMetadataService;
import com.sqlfinder.settings.DatabaseConfigFile;
import com.sqlfinder.settings.MysqlDatabaseConnectionTester;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Sakila查询验收 */
@EnabledIfSystemProperty(named = "sqlfinder.sakila-query", matches = "true")
class SakilaQueryIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static DriverManagerDataSource source;
    private static MetadataProperties metadata;
    private static UserMetadataService userMetadata;
    private static JdbcQueryExecutor executor;
    private static String user;
    private static final SqlValidator validator = new SqlValidator();

    @BeforeAll
    static void connectUsingPrivateSettings() throws Exception {
        var config = DatabaseConfigFile.read(DatabaseConfigFile.DEFAULT_PATH);
        assertEquals("sqlfinder_sakila", config.catalog());
        var diagnostic = new MysqlDatabaseConnectionTester().inspect(config);
        assertEquals("READ_ONLY", diagnostic.privileges());
        assertEquals(16, diagnostic.previews().size());
        source = new DriverManagerDataSource(config.jdbcUrl(), config.username(), config.password());
        metadata = new MetadataProperties();
        metadata.setCatalog(config.catalog());
        metadata.setAllowedTables(config.tables());
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("config/auth.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        var values = new HashMap<String, Object>();
        properties.forEach((k, v) -> values.put(k.toString(), v));
        var accounts = new Binder(new MapConfigurationPropertySource(values))
                .bind("sqlfinder.auth", Bindable.of(AuthProperties.class)).get();
        accounts.validateAccounts();
        user = accounts.users().stream().filter(a -> a.enabled() && a.settingsAdmin())
                .findFirst().orElseThrow().username();
        userMetadata = new UserMetadataService(new JdbcMetadataService(source, metadata), accounts, metadata);
        executor = new JdbcQueryExecutor(source, userMetadata, metadata, validator,
                new QueryExecutionProperties(true, 5, 1000));
    }

    @Test
    void allSampleTablesHaveExpectedCountsAndChineseFieldRemarks() throws Exception {
        var schemas = userMetadata.getAuthorizedSchemas(user);
        assertEquals(16, schemas.size());
        var counts = JSON.readTree(Path.of("samples/sakila/source.json").toFile()).path("expected_rows");
        for (var schema : schemas) {
            assertTrue(schema.columns().stream().anyMatch(c -> c.comment() != null && !c.comment().isBlank()), schema.tableName());
            var sql = validator.validate("SELECT COUNT(*) AS row_count FROM " + schema.tableName(), schemas);
            var result = executor.execute(user, sql);
            assertEquals(counts.path(schema.tableName()).asLong(), ((Number) result.rows().get(0).get("row_count")).longValue(), schema.tableName());
        }
    }

    @Test
    void fourteenQueriesMatchFixedIndependentReferenceResults() throws Exception {
        var schemas = userMetadata.getAuthorizedSchemas(user);
        var cases = JSON.readTree(Path.of("samples/sakila/expected-results.json").toFile());
        assertEquals(14, cases.size());
        for (var reference : cases) {
            String name = reference.path("id").asText();
            var result = executor.execute(user, validator.validate(reference.path("sql").asText(), schemas));
            assertFalse(result.truncated(), name);
            assertEquals(reference.path("columns"), JSON.valueToTree(result.columns()), name);
            var rows = result.rows().stream().map(row -> result.columns().stream()
                    .map(column -> row.get(column) == null ? "NULL" : row.get(column).toString()).toList()).toList();
            assertEquals(reference.path("rows"), JSON.valueToTree(rows), name);
            assertEquals(result.columns().size(), result.columnMetadata().size(), name);
            System.out.println("SAKILA_QUERY_VERIFIED=" + name + "; rows=" + rows.size());
        }
    }

    @Test
    void writeStatementsOtherCatalogsAndUnsafeFunctionsRemainBlocked() {
        var schemas = userMetadata.getAuthorizedSchemas(user);
        for (String sql : List.of("DELETE FROM rental", "SELECT * FROM mybatis.tb_shop", "SELECT SLEEP(1) FROM film")) {
            assertThrows(SqlValidationException.class, () -> validator.validate(sql, schemas));
        }
    }
}
