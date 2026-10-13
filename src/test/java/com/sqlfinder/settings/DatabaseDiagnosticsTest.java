package com.sqlfinder.settings;

import com.sqlfinder.auth.config.AuthProperties;
import com.sqlfinder.datasource.config.MetadataProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 数据库权限诊断测试 */

class DatabaseDiagnosticsTest {
    DataSource ds; Connection connection; Statement identity,table1,table2,grants;
    ResultSet idRows,rows1,rows2,grantRows; MetadataProperties metadata; AuthProperties accounts;
    @BeforeEach void prepare() throws Exception {
        ds=mock(DataSource.class); connection=mock(Connection.class);
        identity=mock(Statement.class); table1=mock(Statement.class); table2=mock(Statement.class); grants=mock(Statement.class);
        idRows=mock(ResultSet.class); rows1=mock(ResultSet.class); rows2=mock(ResultSet.class); grantRows=mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(identity,table1,table2,grants);
        when(identity.executeQuery("SELECT CURRENT_USER(), DATABASE()")).thenReturn(idRows);
        when(idRows.next()).thenReturn(true); when(idRows.getString(1)).thenReturn("reader@localhost"); when(idRows.getString(2)).thenReturn("mybatis");
        when(table1.executeQuery("SELECT * FROM `mybatis`.`tb_shop` LIMIT 0")).thenReturn(rows1);
        when(table2.executeQuery("SELECT * FROM `mybatis`.`tb_shop_type` LIMIT 0")).thenReturn(rows2);
        when(grants.executeQuery("SHOW GRANTS FOR CURRENT_USER()")).thenReturn(grantRows);
        when(grantRows.next()).thenReturn(true,false); when(grantRows.getString(1)).thenReturn("GRANT SELECT ON `mybatis`.* TO 'reader'@'localhost'");
        metadata=new MetadataProperties(); metadata.setCatalog("mybatis"); metadata.setAllowedTables(List.of("tb_shop","tb_shop_type"));
        accounts=new AuthProperties(List.of(new AuthProperties.Account("admin",null,true,List.of("mybatis.tb_shop"),true)));
    }
    private DatabaseDiagnostics service() { return new DatabaseDiagnostics(()->ds,metadata,accounts); }
    @Test void currentConnectionUsesFixedReadQueriesAndSeparatesApplicationPermissions() throws Exception {
        var result=service().check("ADMIN");
        assertEquals("reader@localhost",result.account()); assertEquals("READ_ONLY",result.privileges());
        assertTrue(result.tables().get(0).databaseReadable()); assertTrue(result.tables().get(0).applicationAllowed());
        assertTrue(result.tables().get(1).databaseReadable()); assertFalse(result.tables().get(1).applicationAllowed());
        verify(connection).close(); verify(identity).close(); verify(table1).close(); verify(table2).close(); verify(grants).close();
        verify(rows1).close(); verify(rows2).close(); verify(idRows).close(); verify(grantRows).close();
        verify(rows1,never()).next(); verify(rows2,never()).next();
        verify(connection,never()).setReadOnly(anyBoolean()); verify(connection,never()).setAutoCommit(anyBoolean());
        verify(connection,never()).setCatalog(anyString());
        verify(table1).setQueryTimeout(5); verify(grants).setMaxRows(51);
    }
    @Test void deniedTableDoesNotMaskOtherChecksOrLeakDriverDetails() throws Exception {
        when(table2.executeQuery(anyString())).thenThrow(new SQLException("password=private-value"));
        var result=service().check("admin");
        assertFalse(result.tables().get(1).databaseReadable()); assertTrue(result.tables().get(0).databaseReadable());
        assertFalse(result.toString().contains("private-value")); verify(table2).close();
    }
    @Test void mismatchedDatabaseStopsBeforeAnyBusinessTableRead() throws Exception {
        when(idRows.getString(2)).thenReturn("other");
        assertEquals("DATASOURCE_CATALOG_MISMATCH",assertThrows(ModelSettingsException.class,()->service().check("admin")).code());
        verify(connection,times(1)).createStatement(); verify(connection).close();
    }
    @Test void connectionFailureReturnsFixedError() throws Exception {
        when(ds.getConnection()).thenThrow(new SQLException("jdbc://user:secret@host"));
        var error=assertThrows(ModelSettingsException.class,()->service().check("admin"));
        assertEquals("DATASOURCE_CONNECTION_FAILED",error.code()); assertFalse(error.getMessage().contains("secret")); assertNull(error.getCause());
    }
    @Test void missingDatasourceIsReportedWithoutFallback() {
        var service=new DatabaseDiagnostics(()->null,metadata,accounts);
        assertEquals(503,assertThrows(ModelSettingsException.class,()->service.check("admin")).status());
    }
    @Test void unsafeConfiguredIdentifiersNeverReachJdbc() throws Exception {
        metadata.setCatalog("mybatis`; DROP TABLE x");
        assertThrows(ModelSettingsException.class,()->service().check("admin")); verify(ds,never()).getConnection();
    }
    @Test void grantsFailureIsUnknownAndConnectionIsClosed() throws Exception {
        when(grants.executeQuery(anyString())).thenThrow(new SQLException("private-details"));
        assertEquals("UNKNOWN",service().check("admin").privileges()); verify(grants).close(); verify(connection).close();
    }
    @Test void tooManyGrantsCannotConfirmReadOnly() throws Exception {
        when(grantRows.next()).thenReturn(true);
        assertEquals("UNKNOWN",service().check("admin").privileges());
    }
    @ParameterizedTest @ValueSource(strings={"GRANT INSERT, SELECT ON *.* TO 'reader'@'localhost'",
            "GRANT ALL PRIVILEGES ON *.* TO 'reader'@'localhost'","GRANT SELECT ON *.* TO 'reader'@'localhost' WITH GRANT OPTION",
            "GRANT PROXY ON ''@'' TO 'reader'@'localhost'"})
    void excessivePrivilegesAreFlagged(String grant) { assertEquals("EXTRA_PRIVILEGES",DatabaseDiagnostics.classifyGrants(List.of(grant))); }
    @ParameterizedTest @ValueSource(strings={"GRANT `read_role` TO 'reader'@'localhost'","GRANT SELECT (`id`) ON `mybatis`.`t` TO 'reader'@'localhost'","invalid"})
    void rolesAndColumnGrantsAreNotAssumedToBeComplete(String grant) { assertEquals("UNKNOWN",DatabaseDiagnostics.classifyGrants(List.of(grant))); }
    @Test void explicitReadOnlyGrantsAreRecognized() {
        assertEquals("READ_ONLY",DatabaseDiagnostics.classifyGrants(List.of("GRANT USAGE ON *.* TO 'reader'@'localhost'",
                "GRANT SELECT, SHOW VIEW ON `mybatis`.* TO 'reader'@'localhost'")));
        assertEquals("UNKNOWN",DatabaseDiagnostics.classifyGrants(List.of()));
    }
}
