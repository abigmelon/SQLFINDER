package com.sqlfinder.chat.infrastructure;

import com.sqlfinder.chat.application.QueryExecutionException;
import com.sqlfinder.chat.application.SqlValidationException;
import com.sqlfinder.chat.application.SqlValidator;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import com.sqlfinder.chat.model.QueryResult;
import com.sqlfinder.chat.model.ValidatedSql;
import com.sqlfinder.datasource.application.MetadataService;
import com.sqlfinder.auth.support.TestTableAuthorization;
import com.sqlfinder.auth.application.TableAccessDeniedException;
import com.sqlfinder.datasource.config.MetadataProperties;
import com.sqlfinder.datasource.model.TableSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * JDBC 执行器测试：查询边界、截断与资源释放
 */
class JdbcQueryExecutorTest {
    private static final String SQL = "SELECT id FROM tb_shop";

    private final DataSource dataSource = proxy(DataSource.class);
    private final MetadataService metadataService = proxy(MetadataService.class);
    private final Connection connection = proxy(Connection.class);
    private final PreparedStatement statement = proxy(PreparedStatement.class);
    private final ResultSet rs = proxy(ResultSet.class);
    private final ResultSetMetaData resultMetadata = proxy(ResultSetMetaData.class);
    private final MetadataProperties metadataProperties = new MetadataProperties();
    private final List<TableSchema> schemas = List.of(
            new TableSchema("mybatis", "tb_shop", "", List.of()));
    private final SqlValidator validator = new SqlValidator();

    private static <T> T proxy(Class<T> type) {
        // test/resources 中将默认 mock maker 配置为接口代理。
        return mock(type);
    }

    @BeforeEach
    void setUp() throws SQLException {
        metadataProperties.setCatalog("mybatis");
        metadataProperties.setAllowedTables(List.of("tb_shop", "tb_shop_type"));
        when(metadataService.getAuthorizedSchemas()).thenReturn(schemas);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("mybatis");
        when(connection.prepareStatement(anyString(),
                eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY)))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(resultMetadata);
        when(resultMetadata.getColumnCount()).thenReturn(1);
        when(resultMetadata.getColumnLabel(1)).thenReturn("id");
    }

    private JdbcQueryExecutor executor(boolean enabled, int maxRows) {
        return new JdbcQueryExecutor(dataSource, TestTableAuthorization.forMetadata(metadataService, metadataProperties), metadataProperties,
                validator, new QueryExecutionProperties(enabled, 5, maxRows));
    }

    private ValidatedSql candidate() {
        return validator.validate(SQL, schemas);
    }

    private void verifyResourcesReleased() throws SQLException {
        verify(rs).close();
        verify(statement).close();
        verify(connection).rollback();
        verify(connection).close();
        verify(connection, never()).commit();
    }

    @Test
    void disabledExecutionDoesNotAccessMetadataOrDatabase() {
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(false, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_DISABLED", error.getCode());
        verifyNoInteractions(metadataService, dataSource);
    }

    @Test
    void forgedCandidateIsRevalidatedBeforeOpeningConnection() {
        // 合法摘要不能让 DELETE 通过校验。
        ValidatedSql forged = new ValidatedSql("DELETE FROM tb_shop", candidate().validation());
        assertThrows(SqlValidationException.class,
                () -> executor(true, 2).execute("test-reader", forged));
        verify(metadataService).getAuthorizedSchemas();
        verifyNoInteractions(dataSource);
    }

    @Test
    void wrongConnectionCatalogPreventsExecutionAndClosesConnection() throws SQLException {
        when(connection.getCatalog()).thenReturn("other_db");
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_CATALOG_MISMATCH", error.getCode());
        verify(connection, never()).prepareStatement(anyString(), anyInt(), anyInt());
        verify(connection).close();
        verifyNoInteractions(statement);
    }

    @Test
    void successPreservesNullAndAppliesReadOnlyTimeoutAndRowLimit() throws SQLException {
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getObject(1)).thenReturn(7).thenReturn(null);

        QueryResult result = executor(true, 2).execute("test-reader", candidate());
        assertEquals(List.of("id"), result.columns());
        assertEquals(2, result.rows().size());
        assertEquals(7, result.rows().get(0).get("id"));
        assertTrue(result.rows().get(1).containsKey("id"));
        assertNull(result.rows().get(1).get("id"));
        assertFalse(result.truncated());

        InOrder order = inOrder(connection, statement);
        order.verify(connection).setReadOnly(true);
        order.verify(connection).setAutoCommit(false);
        order.verify(connection).prepareStatement(SQL,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        order.verify(statement).setQueryTimeout(5);
        order.verify(statement).setMaxRows(3);
        order.verify(statement).executeQuery();
        verifyResourcesReleased();
    }

    @Test
    void exactlyMaxRowsDoesNotCountAsTruncation() throws SQLException {
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getObject(1)).thenReturn(1, 2);
        QueryResult result = executor(true, 2).execute("test-reader", candidate());
        assertEquals(2, result.rows().size());
        assertFalse(result.truncated());
        verifyResourcesReleased();
    }

    @Test
    void extraRowMarksTruncationWithoutReadingItsValues() throws SQLException {
        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getObject(1)).thenReturn(1, 2);
        QueryResult result = executor(true, 2).execute("test-reader", candidate());
        assertEquals(2, result.rows().size());
        assertTrue(result.truncated());
        assertEquals(2, result.rows().get(1).get("id"));
        verify(rs, times(2)).getObject(1);
        verifyResourcesReleased();
    }

    @Test
    void emptyResultKeepsColumnLabels() throws SQLException {
        when(rs.next()).thenReturn(false);
        QueryResult result = executor(true, 2).execute("test-reader", candidate());
        assertEquals(List.of("id"), result.columns());
        assertTrue(result.rows().isEmpty());
        assertFalse(result.truncated());
        verifyResourcesReleased();
    }

    @Test
    void timeoutRollsBackAndClosesAcquiredResources() throws SQLException {
        when(statement.executeQuery()).thenThrow(new SQLTimeoutException("driver detail"));
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_TIMEOUT", error.getCode());
        assertFalse(error.getMessage().contains("driver detail"));
        verify(statement).close();
        verify(connection).rollback();
        verify(connection).close();
        verify(connection, never()).commit();
        // 没有取得 ResultSet，不应尝试关闭它。
        verifyNoInteractions(rs);
    }

    @Test
    void readFailureAlsoReleasesResultSetAndRollsBack() throws SQLException {
        when(rs.next()).thenReturn(true);
        when(rs.getObject(1)).thenThrow(new SQLException("read failed"));
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_FAILED", error.getCode());
        verifyResourcesReleased();
    }

    @Test
    void duplicateColumnLabelsAreRejectedBeforeReadingRows() throws SQLException {
        when(resultMetadata.getColumnCount()).thenReturn(2);
        when(resultMetadata.getColumnLabel(2)).thenReturn("id");
        ValidatedSql repeatedColumns = validator.validate("SELECT id, id FROM tb_shop", schemas);
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", repeatedColumns));
        assertEquals("QUERY_DUPLICATE_COLUMN", error.getCode());
        verify(rs, never()).next();
        verifyResourcesReleased();
    }

    @Test
    void oversizedTextIsRejectedAndResourcesAreReleased() throws SQLException {
        when(rs.next()).thenReturn(true);
        when(rs.getObject(1)).thenReturn("x".repeat(16_385));
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_RESULT_TOO_LARGE", error.getCode());
        verifyResourcesReleased();
    }

    @Test
    void rollbackFailureDoesNotReplaceOriginalQueryFailure() throws SQLException {
        SQLException queryFailure = new SQLException("original failure");
        SQLException rollbackFailure = new SQLException("rollback failure");
        when(statement.executeQuery()).thenThrow(queryFailure);
        doThrow(rollbackFailure).when(connection).rollback();
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_FAILED", error.getCode());
        assertSame(queryFailure, error.getCause());
        assertEquals(1, queryFailure.getSuppressed().length);
        assertSame(rollbackFailure, queryFailure.getSuppressed()[0]);
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void nullCandidateDoesNotAccessMetadataOrDatabase() {
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", null));
        assertEquals("QUERY_INVALID_INPUT", error.getCode());
        verifyNoInteractions(metadataService, dataSource);
    }

    @Test
    void metadataFailureKeepsDriverDetailsOutOfMessage() {
        RuntimeException failure = new IllegalStateException("internal connection detail");
        when(metadataService.getAuthorizedSchemas()).thenThrow(failure);
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_METADATA_FAILED", error.getCode());
        assertSame(failure, error.getCause());
        assertFalse(error.getMessage().contains("internal connection detail"));
        verifyNoInteractions(dataSource);
    }

    @Test
    void changedAuthorizationInvalidatesPreviouslyValidCandidate() {
        when(metadataService.getAuthorizedSchemas()).thenReturn(List.of(
                new TableSchema("mybatis", "tb_shop_type", "", List.of())));
        SqlValidationException error = assertThrows(SqlValidationException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("SQL_UNAUTHORIZED_TABLE", error.getCode());
        verifyNoInteractions(dataSource);
    }

    @Test
    void removedGlobalCatalogGrantDoesNotOpenQueryConnection() {
        metadataProperties.setCatalog("other_db");
        assertThrows(TableAccessDeniedException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        verifyNoInteractions(metadataService, dataSource);
    }

    @Test
    void connectionAcquisitionFailureIsReportedWithoutExecuting() throws SQLException {
        SQLException failure = new SQLException("internal connection detail");
        when(dataSource.getConnection()).thenThrow(failure);
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_FAILED", error.getCode());
        assertSame(failure, error.getCause());
        assertFalse(error.getMessage().contains("internal connection detail"));
        verifyNoInteractions(connection, statement, rs);
    }

    @Test
    void readOnlySetupFailureClosesConnectionWithoutExecuting() throws SQLException {
        doThrow(new SQLException("read-only setup failed")).when(connection).setReadOnly(true);
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_FAILED", error.getCode());
        verify(connection).close();
        verify(connection, never()).setAutoCommit(false);
        verifyNoInteractions(statement, rs);
    }

    @Test
    void transactionSetupFailureClosesConnectionWithoutExecuting() throws SQLException {
        doThrow(new SQLException("transaction setup failed")).when(connection).setAutoCommit(false);
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_FAILED", error.getCode());
        verify(connection).close();
        verifyNoInteractions(statement, rs);
    }

    @Test
    void statementCreationFailureRollsBackAndClosesConnection() throws SQLException {
        when(connection.prepareStatement(anyString(), anyInt(), anyInt()))
                .thenThrow(new SQLException("prepare failed"));
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_FAILED", error.getCode());
        verify(connection).rollback();
        verify(connection).close();
        verifyNoInteractions(statement, rs);
    }

    @Test
    void resultSetCloseFailurePreventsReturningSuccess() throws SQLException {
        when(rs.next()).thenReturn(false);
        doThrow(new SQLException("result close failed")).when(rs).close();
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_FAILED", error.getCode());
        verifyResourcesReleased();
    }

    @Test
    void successfulQueryWithRollbackFailureDoesNotReturnResult() throws SQLException {
        when(rs.next()).thenReturn(false);
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_FAILED", error.getCode());
        verifyResourcesReleased();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void invalidColumnCountIsRejected(int count) throws SQLException {
        when(resultMetadata.getColumnCount()).thenReturn(count);
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_RESULT_TOO_LARGE", error.getCode());
        verify(rs, never()).next();
        verifyResourcesReleased();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingColumnLabelIsRejected(String label) throws SQLException {
        when(resultMetadata.getColumnLabel(1)).thenReturn(label);
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_INVALID_COLUMN", error.getCode());
        verifyResourcesReleased();
    }

    @Test
    void distinctAliasesPreserveBothValuesAndTheirOrder() throws SQLException {
        when(resultMetadata.getColumnCount()).thenReturn(2);
        when(resultMetadata.getColumnLabel(1)).thenReturn("shop_id");
        when(resultMetadata.getColumnLabel(2)).thenReturn("type_id");
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(8);
        when(rs.getObject(2)).thenReturn(3);
        ValidatedSql aliased = validator.validate("SELECT id AS shop_id, type_id FROM tb_shop", schemas);
        QueryResult result = executor(true, 2).execute("test-reader", aliased);
        assertEquals(List.of("shop_id", "type_id"), result.columns());
        assertEquals(List.of("shop_id", "type_id"), List.copyOf(result.rows().get(0).keySet()));
        assertEquals(8, result.rows().get(0).get("shop_id"));
        assertEquals(3, result.rows().get(0).get("type_id"));
        verifyResourcesReleased();
    }

    static Stream<Arguments> supportedValues() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of(true, true),
                Arguments.of((byte) 7, (byte) 7),
                Arguments.of((short) 8, (short) 8),
                Arguments.of(9, 9),
                Arguments.of(10L, 10L),
                Arguments.of(1.5F, 1.5F),
                Arguments.of(2.5D, 2.5D),
                Arguments.of(new java.math.BigDecimal("123.45"), new java.math.BigDecimal("123.45")),
                Arguments.of(new java.math.BigInteger("18446744073709551615"),
                        new java.math.BigInteger("18446744073709551615")),
                Arguments.of("中文商铺", "中文商铺"),
                Arguments.of(new byte[]{0, 1, 2}, "AAEC"),
                Arguments.of(java.sql.Date.valueOf("2026-10-10"), "2026-10-10"),
                Arguments.of(java.sql.Time.valueOf("12:34:56"), "12:34:56"),
                Arguments.of(java.sql.Timestamp.valueOf("2026-10-10 12:34:56.123"),
                        "2026-10-10 12:34:56.123"),
                Arguments.of(java.time.LocalDate.of(2026, 10, 10), "2026-10-10"),
                Arguments.of(java.time.LocalDateTime.of(2026, 10, 10, 12, 34, 56),
                        "2026-10-10T12:34:56"),
                Arguments.of(java.time.OffsetDateTime.parse("2026-10-10T12:34:56+08:00"),
                        "2026-10-10T12:34:56+08:00")
        );
    }

    @ParameterizedTest
    @MethodSource("supportedValues")
    void supportedJdbcValuesRemainJsonSerializable(Object jdbcValue, Object expected) throws Exception {
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(jdbcValue);
        QueryResult result = executor(true, 2).execute("test-reader", candidate());
        assertEquals(expected, result.rows().get(0).get("id"));
        ObjectMapper mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(result));
        // JSON 数字不保留 Java 的 Long/Float/BigDecimal 节点类型。
        assertEquals(mapper.readTree(mapper.writeValueAsString(expected)),
                json.path("rows").get(0).path("id"));
        verifyResourcesReleased();
    }

    static Stream<Number> nonFiniteNumbers() {
        return Stream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
    }

    @ParameterizedTest
    @MethodSource("nonFiniteNumbers")
    void nonFiniteNumbersAreRejected(Number value) throws SQLException {
        when(rs.next()).thenReturn(true);
        when(rs.getObject(1)).thenReturn(value);
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_UNSUPPORTED_VALUE", error.getCode());
        verifyResourcesReleased();
    }

    @Test
    void driverObjectsAreRejectedInsteadOfBeingSerialized() throws SQLException {
        when(rs.next()).thenReturn(true);
        when(rs.getObject(1)).thenReturn(proxy(Blob.class));
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_UNSUPPORTED_VALUE", error.getCode());
        verifyResourcesReleased();
    }

    @Test
    void oversizedBinaryIsRejected() throws SQLException {
        when(rs.next()).thenReturn(true);
        when(rs.getObject(1)).thenReturn(new byte[16_385]);
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 2).execute("test-reader", candidate()));
        assertEquals("QUERY_RESULT_TOO_LARGE", error.getCode());
        verifyResourcesReleased();
    }

    @Test
    void textExactlyAtCellLimitIsAccepted() throws SQLException {
        String text = "x".repeat(16_384);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(text);
        QueryResult result = executor(true, 2).execute("test-reader", candidate());
        assertEquals(text, result.rows().get(0).get("id"));
        verifyResourcesReleased();
    }

    @Test
    void combinedResultSizeIsBoundedEvenWhenIndividualCellsFit() throws SQLException {
        AtomicInteger cursor = new AtomicInteger();
        when(rs.next()).thenAnswer(invocation -> cursor.incrementAndGet() <= 100);
        when(rs.getObject(1)).thenReturn("x".repeat(16_384));
        QueryExecutionException error = assertThrows(QueryExecutionException.class,
                () -> executor(true, 1000).execute("test-reader", candidate()));
        assertEquals("QUERY_RESULT_TOO_LARGE", error.getCode());
        verifyResourcesReleased();
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"unknown", "no-grants", "disabled-reader"})
    void unauthorizedIdentityDoesNotReadMetadataOrOpenQueryConnection(String userId) {
        assertThrows(TableAccessDeniedException.class, () -> executor(true, 2).execute(userId, candidate()));
        verifyNoInteractions(metadataService, dataSource);
    }

    @Test void validCandidateFromAnotherUserCannotReuseItsSummary() {
        when(metadataService.getAuthorizedSchemas()).thenReturn(List.of(
                schemas.get(0), new TableSchema("mybatis", "tb_shop_type", "", List.of())));
        SqlValidationException error = assertThrows(SqlValidationException.class,
                () -> executor(true, 2).execute("second-reader", candidate()));
        assertEquals("SQL_UNAUTHORIZED_TABLE", error.getCode());
        verifyNoInteractions(dataSource);
    }

    @Test void narrowedAccountConfigurationRejectsPreviouslyAuthorizedCandidate() {
        var accounts = new com.sqlfinder.auth.config.AuthProperties(List.of(
                new com.sqlfinder.auth.config.AuthProperties.Account("test-reader", null, true,
                        List.of("mybatis.tb_shop_type"))));
        when(metadataService.getAuthorizedSchemas()).thenReturn(List.of(
                schemas.get(0), new TableSchema("mybatis", "tb_shop_type", "", List.of())));
        var scoped = new com.sqlfinder.datasource.application.UserMetadataService(metadataService, accounts, metadataProperties);
        var narrowedExecutor = new JdbcQueryExecutor(dataSource, scoped, metadataProperties, validator,
                new QueryExecutionProperties(true, 5, 2));
        assertThrows(SqlValidationException.class, () -> narrowedExecutor.execute("test-reader", candidate()));
        verifyNoInteractions(dataSource);
    }

    @Test void globalRevocationAfterValidationRejectsCandidateBeforeQueryConnection() {
        ValidatedSql prior = candidate();
        metadataProperties.setAllowedTables(List.of("tb_shop_type"));
        when(metadataService.getAuthorizedSchemas()).thenReturn(List.of(
                new TableSchema("mybatis", "tb_shop_type", "", List.of())));
        assertThrows(SqlValidationException.class, () -> executor(true, 2).execute("test-reader", prior));
        verifyNoInteractions(dataSource);
    }
}
