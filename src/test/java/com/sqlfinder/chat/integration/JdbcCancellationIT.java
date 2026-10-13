package com.sqlfinder.chat.integration;

import com.sqlfinder.auth.config.AuthProperties;
import com.sqlfinder.chat.application.RequestExecution;
import com.sqlfinder.chat.application.RequestStoppedException;
import com.sqlfinder.chat.application.SqlValidator;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import com.sqlfinder.chat.infrastructure.JdbcQueryExecutor;
import com.sqlfinder.chat.model.QueryResult;
import com.sqlfinder.chat.model.ValidatedSql;
import com.sqlfinder.datasource.application.UserMetadataService;
import com.sqlfinder.datasource.config.MetadataProperties;
import com.sqlfinder.datasource.infrastructure.JdbcMetadataService;
import com.sqlfinder.datasource.model.TableSchema;
import com.sqlfinder.settings.DatabaseConfigFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库取消 */
@EnabledIfSystemProperty(named = "sqlfinder.sakila-query", matches = "true")
class JdbcCancellationIT {
    @Test void productionExecutorCancelsLiveMysqlStatementAndReleasesConnection() throws Exception {
        var config = DatabaseConfigFile.read(DatabaseConfigFile.DEFAULT_PATH);
        assertEquals("sqlfinder_sakila", config.catalog());
        var source = new DriverManagerDataSource(config.jdbcUrl(), config.username(), config.password());
        var metadata = new MetadataProperties();
        metadata.setCatalog(config.catalog());
        metadata.setAllowedTables(config.tables());
        var grants = config.tables().stream().map(table -> config.catalog() + "." + table).toList();
        var accounts = new AuthProperties(List.of(new AuthProperties.Account("cancel-test", null, true, grants)));
        var schemas = new UserMetadataService(new JdbcMetadataService(source, metadata), accounts, metadata);
        String slowSql = "SELECT SLEEP(10) AS waiting FROM film LIMIT 1";
        // 仅本验收替换校验器以制造稳定慢查询；生产安全规则依然禁止 SLEEP。
        var safe = new SqlValidator().validate("SELECT film_id FROM film LIMIT 1", schemas.getAuthorizedSchemas("cancel-test"));
        var testValidator = new SqlValidator() {
            @Override public ValidatedSql validate(String sql, List<TableSchema> authorized) {
                if (!slowSql.equals(sql)) throw new IllegalArgumentException("Unexpected cancellation probe");
                return new ValidatedSql(sql, safe.validation());
            }
        };
        var connectionId = new AtomicLong();
        var closedConnections = new AtomicInteger();
        var rolledBack = new AtomicInteger();
        var driverCancellations = new AtomicInteger();
        var observed = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = source.getConnection();
                try (var statement = actual.createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    assertTrue(rows.next()); connectionId.set(rows.getLong(1));
                }
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, arguments) -> {
                            try {
                                Object result = method.invoke(actual, arguments);
                                if (method.getName().equals("close")) closedConnections.incrementAndGet();
                                if (method.getName().equals("rollback")) rolledBack.incrementAndGet();
                                if (method.getName().equals("prepareStatement")) {
                                    PreparedStatement statement = (PreparedStatement) result;
                                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                            (statementProxy, statementMethod, statementArguments) -> {
                                                try {
                                                    if (statementMethod.getName().equals("cancel")) driverCancellations.incrementAndGet();
                                                    return statementMethod.invoke(statement, statementArguments);
                                                } catch (InvocationTargetException e) { throw e.getCause(); }
                                            });
                                }
                                return result;
                            } catch (InvocationTargetException e) { throw e.getCause(); }
                        });
            }
            @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        };
        var query = new JdbcQueryExecutor(observed, schemas, metadata, testValidator,
                new QueryExecutionProperties(true, 15, 5));
        var execution = new RequestExecution(UUID.randomUUID(), "cancel-test", Duration.ofSeconds(15));
        var task = new FutureTask<QueryResult>(() -> {
            try (var scope = execution.bind()) { return query.execute("cancel-test", new ValidatedSql(slowSql, safe.validation())); }
        });
        var worker = new Thread(task);
        worker.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            boolean running = false;
            try (Connection monitor = source.getConnection()) {
                while (!running && System.nanoTime() < deadline) {
                    try (var statement = monitor.createStatement(); var rows = statement.executeQuery("SHOW PROCESSLIST")) {
                        while (rows.next()) {
                            if (rows.getLong("Id") == connectionId.get() && slowSql.equals(rows.getString("Info"))) running = true;
                        }
                    }
                    if (!running) Thread.sleep(20);
                }
            }
            assertTrue(running, "The test must observe a running statement before cancelling it");
            execution.cancel();
            var failure = assertThrows(ExecutionException.class, () -> task.get(3, TimeUnit.SECONDS));
            assertEquals("REQUEST_CANCELLED", assertInstanceOf(RequestStoppedException.class, failure.getCause()).code());
            assertEquals(1, driverCancellations.get());
            assertEquals(1, rolledBack.get());
            assertEquals(1, closedConnections.get());
            System.out.println("MYSQL_LIVE_CANCEL_VERIFIED");
        } finally { execution.cancel(); worker.join(5000); }
    }
}
