package com.sqlfinder.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 数据库连接检测测试 */

class MysqlDatabaseConnectionTesterTest {
    Connection connection; Statement identity,preview,grants; PreparedStatement discovery;
    ResultSet ids,tables,columns,permissions; ResultSetMetaData metadata;
    DatabaseConfiguration config=new DatabaseConfiguration("localhost",3306,"db","reader","secret",false,List.of("shop"));
    @BeforeEach void prepare() throws Exception {
        connection=mock(Connection.class); identity=mock(Statement.class); preview=mock(Statement.class); grants=mock(Statement.class);
        discovery=mock(PreparedStatement.class); ids=mock(ResultSet.class); tables=mock(ResultSet.class); columns=mock(ResultSet.class); permissions=mock(ResultSet.class); metadata=mock(ResultSetMetaData.class);
        when(connection.createStatement()).thenReturn(identity,preview,grants); when(connection.prepareStatement(anyString())).thenReturn(discovery);
        when(identity.executeQuery("SELECT CURRENT_USER(), DATABASE()")).thenReturn(ids);
        when(ids.next()).thenReturn(true); when(ids.getString(1)).thenReturn("reader@localhost"); when(ids.getString(2)).thenReturn("db");
        when(discovery.executeQuery()).thenReturn(tables); when(tables.next()).thenReturn(true,false); when(tables.getString(1)).thenReturn("shop");
        when(preview.executeQuery("SELECT * FROM `db`.`shop` LIMIT 0")).thenReturn(columns);
        when(columns.getMetaData()).thenReturn(metadata); when(metadata.getColumnCount()).thenReturn(1); when(metadata.getColumnName(1)).thenReturn("amount");
        when(metadata.getColumnTypeName(1)).thenReturn("DECIMAL"); when(metadata.isNullable(1)).thenReturn(ResultSetMetaData.columnNoNulls);
        when(grants.executeQuery("SHOW GRANTS FOR CURRENT_USER()")).thenReturn(permissions); when(permissions.next()).thenReturn(true,false);
        when(permissions.getString(1)).thenReturn("GRANT SELECT ON `db`.* TO 'reader'@'localhost'");
    }
    MysqlDatabaseConnectionTester tester() { return new MysqlDatabaseConnectionTester(c->connection); }
    @Test void inspectBindsCatalogAndReadsSchemaWithoutFetchingBusinessRows() throws Exception {
        var result=tester().inspect(config);
        assertEquals(List.of("shop"),result.tables()); assertEquals("READ_ONLY",result.privileges());
        assertEquals("DECIMAL",result.previews().get(0).columns().get(0).type()); assertFalse(result.previews().get(0).columns().get(0).nullable());
        verify(columns,never()).next(); verify(discovery).setString(1,"db"); verify(discovery).setMaxRows(201); verify(discovery).setQueryTimeout(5);
        verify(connection).close(); verify(identity).close(); verify(preview).close(); verify(discovery).close(); verify(grants).close();
        verify(ids).close(); verify(tables).close(); verify(columns).close(); verify(permissions).close();
        verify(connection,never()).setCatalog(anyString());
    }
    @Test void incorrectCatalogStopsBeforeDiscoveryAndClosesResources() throws Exception {
        when(ids.getString(2)).thenReturn("wrong");
        assertEquals("DATASOURCE_CATALOG_MISMATCH",assertThrows(ModelSettingsException.class,()->tester().inspect(config)).code());
        verify(connection,never()).prepareStatement(anyString()); verify(connection).close(); verify(ids).close();
    }
    @Test void unavailableTableStopsBeforeRead() throws Exception {
        when(tables.getString(1)).thenReturn("other");
        assertEquals("DATABASE_TABLE_UNAVAILABLE",assertThrows(ModelSettingsException.class,()->tester().inspect(config)).code());
        verify(preview,never()).executeQuery(anyString()); verify(connection).close();
    }
    @Test void selectedTableReadFailureIsSafeAndClosesStatementAndConnection() throws Exception {
        when(preview.executeQuery(anyString())).thenThrow(new SQLException("jdbc password=secret"));
        var ex=assertThrows(ModelSettingsException.class,()->tester().inspect(config)); assertNull(ex.getCause()); assertFalse(ex.toString().contains("secret"));
        verify(preview).close(); verify(connection).close();
    }
    @Test void connectionFailureDoesNotLeakDriverMessage() {
        var tester=new MysqlDatabaseConnectionTester(c->{throw new SQLException("password=secret");});
        var ex=assertThrows(ModelSettingsException.class,()->tester.inspect(config)); assertFalse(ex.toString().contains("secret")); assertNull(ex.getCause());
    }
    @Test void failedGrantInspectionDoesNotPretendReadonly() throws Exception {
        when(grants.executeQuery(anyString())).thenThrow(new SQLException("restricted"));
        assertEquals("UNKNOWN",tester().inspect(config).privileges()); verify(grants).close(); verify(connection).close();
    }
    @Test void unsafeTableNamesAreExcludedFromDiscovery() throws Exception {
        when(tables.next()).thenReturn(true,true,false); when(tables.getString(1)).thenReturn("shop","bad-name");
        assertEquals(List.of("shop"),tester().inspect(config).tables());
    }
    @Test void discoveryIsBoundedAndReportsTruncation() throws Exception {
        var n=new java.util.concurrent.atomic.AtomicInteger(); when(tables.next()).thenAnswer(i->n.incrementAndGet()<=201);
        when(tables.getString(1)).thenAnswer(i->"t"+n.get());
        var empty=new DatabaseConfiguration("localhost",3306,"db","reader","secret",false,List.of());
        // Without previews the second regular Statement is for grants.
        when(connection.createStatement()).thenReturn(identity,grants);
        var result=tester().inspect(empty); assertEquals(200,result.tables().size()); assertTrue(result.truncated()); assertTrue(result.previews().isEmpty());
    }
    @Test void excessivelyWideSchemaCannotBeSaved() throws Exception {
        when(metadata.getColumnCount()).thenReturn(201);
        assertEquals("DATABASE_COLUMNS_LIMIT",assertThrows(ModelSettingsException.class,()->tester().inspect(config)).code());
        verify(columns).close(); verify(connection).close();
    }
}
