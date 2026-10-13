package com.sqlfinder.chat.integration;

import com.sqlfinder.SqlFinderApplication;
import com.sqlfinder.chat.application.QueryExecutionException;
import com.sqlfinder.chat.application.SqlValidator;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import com.sqlfinder.chat.infrastructure.JdbcQueryExecutor;
import com.sqlfinder.chat.model.QueryResult;
import com.sqlfinder.datasource.application.MetadataService;
import com.sqlfinder.datasource.application.UserMetadataService;
import com.sqlfinder.auth.support.SecurityTestAccounts;
import org.springframework.context.annotation.Import;
import com.sqlfinder.datasource.config.MetadataProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** 真实查询执行验收 */
@EnabledIfSystemProperty(named = "sqlfinder.live-query", matches = "true")
@ActiveProfiles("local")
@Import(SecurityTestAccounts.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = SqlFinderApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"sqlfinder.query.enabled=true", "sqlfinder.query.max-rows=2",
                "sqlfinder.query.timeout-seconds=1",
                "spring.datasource.hikari.maximum-pool-size=1",
                "spring.datasource.hikari.minimum-idle=1", "spring.main.banner-mode=off"})
class RealQueryExecutionIT {
    @Autowired DataSource dataSource;
    @Autowired MetadataService metadataService;
    @Autowired UserMetadataService userMetadataService;
    @Autowired MetadataProperties metadataProperties;
    @Autowired SqlValidator validator;

    private TrackingDataSource tracking;
    private JdbcQueryExecutor executor;
    private long connectionId;
    private boolean autoCommit;
    private boolean readOnly;
    private long sessionLimit;
    private int networkTimeout;

    @BeforeEach
    void verifyReadOnlyAccountAndRecordConnectionState() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            autoCommit = connection.getAutoCommit();
            readOnly = connection.isReadOnly();
            networkTimeout = connection.getNetworkTimeout();
            try (ResultSet rs = statement.executeQuery(
                    "SELECT CURRENT_USER(), DATABASE(), CONNECTION_ID(), @@SESSION.max_execution_time")) {
                assertTrue(rs.next());
                assertEquals("sqlfinder_reader@localhost", rs.getString(1));
                assertEquals("mybatis", rs.getString(2));
                connectionId = rs.getLong(3);
                sessionLimit = rs.getLong(4);
            }
            try (ResultSet rs = statement.executeQuery("SHOW GRANTS")) {
                int grants = 0;
                while (rs.next()) {
                    grants++;
                    String grant = rs.getString(1);
                    assertTrue(grant.matches("(?i)GRANT (USAGE|SELECT) ON .+ TO .+")
                                    && !grant.toUpperCase(Locale.ROOT).contains("WITH GRANT OPTION"),
                            "真实测试要求已有的专用 SELECT/USAGE 账号");
                }
                assertTrue(grants > 0);
            }
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM tb_shop")) {
                assertTrue(rs.next());
                assertEquals(14, rs.getLong(1), "本验收依赖 14 行样例；数据改变后先重新核对");
            }
        }
        tracking = new TrackingDataSource(dataSource);
        executor = new JdbcQueryExecutor(tracking, userMetadataService, metadataProperties,
                validator, new QueryExecutionProperties(true, 1, 2));
    }

    private QueryResult query(String sql) {
        return executor.execute(SecurityTestAccounts.USERNAME, validator.validate(sql, metadataService.getAuthorizedSchemas()));
    }

    @Test
    void extraRowIsTruncatedAndAllJdbcResourcesAreClosed() throws Exception {
        QueryResult result = query("SELECT id FROM tb_shop ORDER BY id");
        assertEquals(List.of("id"), result.columns());
        assertEquals(2, result.rows().size());
        assertTrue(result.truncated());
        assertTrue(((Number) result.rows().get(0).get("id")).longValue()
                < ((Number) result.rows().get(1).get("id")).longValue());
        assertCleanup(1);
        assertPoolRecovered();
    }

    @Test
    void explicitLimitDoesNotFalselyReportTruncation() throws Exception {
        QueryResult result = query("SELECT id FROM tb_shop ORDER BY id LIMIT 2");
        assertEquals(2, result.rows().size());
        assertFalse(result.truncated());
        assertCleanup(1);
        assertPoolRecovered();
    }

    @Test
    void emptyResultRetainsColumnNames() throws Exception {
        QueryResult result = query("SELECT id, name FROM tb_shop WHERE 1 = 0");
        assertEquals(List.of("id", "name"), result.columns());
        assertTrue(result.rows().isEmpty());
        assertFalse(result.truncated());
        assertCleanup(1);
        assertPoolRecovered();
    }

    @Test
    void nonexistentColumnRollsBackAndNextQuerySucceeds() throws Exception {
        // 当前策略只检查表范围，不验证所有字段；由真实数据库返回字段错误。
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> query("SELECT sqlfinder_missing_column_for_test FROM tb_shop"));
        assertEquals("QUERY_FAILED", error.getCode());
        assertInstanceOf(SQLException.class, error.getCause());
        assertCleanup(0);
        assertPoolRecovered();
        assertNextQuerySucceeds();
    }

    @Test
    void duplicateLabelsRollBackAndNextQuerySucceeds() throws Exception {
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> query("SELECT id AS duplicate, name AS duplicate FROM tb_shop LIMIT 1"));
        assertEquals("QUERY_DUPLICATE_COLUMN", error.getCode());
        assertCleanup(1);
        assertPoolRecovered();
        assertNextQuerySucceeds();
    }

    @Test
    void realDriverCancelsTimeoutAndPoolReplacesConnection() throws Exception {
        // 不开放 SLEEP 等函数。14^8 次组合逐行求和，驱动 1 秒取消；
        // 测试连接另外设置 MySQL 3 秒上限、网络 5 秒上限，并在归还前恢复。
        tracking.boundedTimeout = true;
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> query("SELECT SUM(a.id + b.id + c.id + d.id + e.id + f.id + g.id + h.id) AS n "
                        + "FROM tb_shop a CROSS JOIN tb_shop b "
                        + "CROSS JOIN tb_shop c CROSS JOIN tb_shop d CROSS JOIN tb_shop e "
                        + "CROSS JOIN tb_shop f CROSS JOIN tb_shop g CROSS JOIN tb_shop h"));
        assertEquals("QUERY_TIMEOUT", error.getCode());
        assertInstanceOf(SQLTimeoutException.class, error.getCause());
        assertCleanup(0);
        assertPoolRecovered(false);
        tracking.boundedTimeout = false;
        assertNextQuerySucceeds();
    }

    private void assertCleanup(int resultSets) {
        assertEquals(1, tracking.rollbacks, "真实连接必须收到 rollback");
        assertEquals(1, tracking.connectionCloses);
        assertEquals(1, tracking.statementCloses);
        assertEquals(resultSets, tracking.resultSetCloses);
    }

    private void assertNextQuerySucceeds() throws Exception {
        QueryResult result = query("SELECT COUNT(*) AS n FROM tb_shop");
        assertEquals(14, ((Number) result.rows().get(0).get("n")).longValue());
        assertFalse(result.truncated());
        assertPoolRecovered();
    }

    private void assertPoolRecovered() throws Exception {
        assertPoolRecovered(true);
    }

    private void assertPoolRecovered(boolean sameConnection) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(autoCommit, connection.getAutoCommit());
            assertEquals(readOnly, connection.isReadOnly());
            assertEquals(networkTimeout, connection.getNetworkTimeout());
            assertEquals("mybatis", connection.getCatalog());
            statement.setQueryTimeout(5);
            try (ResultSet rs = statement.executeQuery(
                    "SELECT CONNECTION_ID(), @@SESSION.max_execution_time")) {
                assertTrue(rs.next());
                if (sameConnection) {
                    assertEquals(connectionId, rs.getLong(1), "普通失败后应复用同一物理连接");
                } else {
                    assertNotEquals(connectionId, rs.getLong(1), "当前 Hikari 配置会淘汰超时连接");
                    connectionId = rs.getLong(1);
                }
                assertEquals(sessionLimit, rs.getLong(2), "测试的会话超时必须恢复");
            }
        }
    }

    /** 只跟踪调用并转发到真实驱动，不模拟返回值或数据库异常。 */
    private static class TrackingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        int rollbacks, connectionCloses, statementCloses, resultSetCloses;
        boolean boundedTimeout;

        TrackingDataSource(DataSource delegate) { this.delegate = delegate; }

        @Override
        public Connection getConnection() throws SQLException { return track(delegate.getConnection()); }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return track(delegate.getConnection(username, password));
        }

        private Connection track(Connection connection) throws SQLException {
            int originalNetwork = connection.getNetworkTimeout();
            long originalLimit = 0;
            boolean bounded = boundedTimeout;
            if (bounded) {
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(5);
                    try (ResultSet rs = statement.executeQuery("SELECT @@SESSION.max_execution_time")) {
                        if (!rs.next()) throw new SQLException("缺少会话超时值");
                        originalLimit = rs.getLong(1);
                    }
                    statement.execute("SET SESSION max_execution_time = 3000");
                    connection.setNetworkTimeout(Runnable::run, 5000);
                } catch (SQLException | RuntimeException e) {
                    discard(connection, e);
                    throw e;
                }
            }
            long restoreLimit = originalLimit;
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("rollback")) rollbacks++;
                        if (method.getName().equals("close")) {
                            connectionCloses++;
                            try {
                                if (bounded) {
                                    try (Statement statement = connection.createStatement()) {
                                        statement.setQueryTimeout(5);
                                        statement.execute("SET SESSION max_execution_time = " + restoreLimit);
                                    }
                                    connection.setNetworkTimeout(Runnable::run, originalNetwork);
                                }
                            } catch (SQLException | RuntimeException e) {
                                discard(connection, e);
                                throw e;
                            } finally {
                                connection.close();
                            }
                            return null;
                        }
                        Object result = invoke(connection, method, args);
                        if (result instanceof PreparedStatement statement) return track(statement);
                        return result;
                    });
        }

        private PreparedStatement track(PreparedStatement statement) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close")) statementCloses++;
                        Object result = invoke(statement, method, args);
                        if (result instanceof ResultSet rs) return track(rs);
                        return result;
                    });
        }

        private ResultSet track(ResultSet rs) {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close")) resultSetCloses++;
                        return invoke(rs, method, args);
                    });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private static void discard(Connection connection, Exception original) {
            // 会话恢复失败时丢弃物理连接，避免污染后续借用者。
            try { connection.abort(Runnable::run); }
            catch (SQLException e) { original.addSuppressed(e); }
            try { connection.close(); }
            catch (SQLException e) { original.addSuppressed(e); }
        }
    }
}
