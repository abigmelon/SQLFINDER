package com.sqlfinder.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 数据库设置管理测试 */

class DatabaseSettingsServiceTest {
    @TempDir Path dir;
    DatabaseConfiguration active=new DatabaseConfiguration("127.0.0.1",3306,"old_db","reader","old-secret",false,List.of("shop"));
    DatabaseSettingsRequest request(String host,String catalog,String user,String password,boolean clear,List<String> tables) {
        return new DatabaseSettingsRequest(host,3306,catalog,user,password,clear,false,tables);
    }
    DatabaseConnectionTester tester= c -> new DatabaseConnectionTester.Result("reader@localhost",c.catalog(),"READ_ONLY",List.of("shop"),false,List.of());
    DatabaseSettingsService service() { return new DatabaseSettingsService(active,dir.resolve("database.properties"),tester); }
    @Test void savePersistsExactPasswordAndDoesNotChangeActiveConnection() throws Exception {
        var s=service();
        var r=request("db.example","new_db","reader","space = # 密码",false,List.of("shop"));
        var view=s.save(r);
        assertTrue(view.pendingRestart()); assertEquals("old_db",view.active().catalog()); assertEquals("new_db",view.saved().catalog());
        var saved=DatabaseConfigFile.read(dir.resolve("database.properties"));
        assertEquals("space = # 密码",saved.password());
        assertFalse(saved.toString().contains("space")); assertFalse(r.toString().contains("space"));
        var restarted=new DatabaseSettingsService(saved,dir.resolve("database.properties"),tester);
        assertFalse(restarted.view().pendingRestart());
        assertEquals("new_db",restarted.view().active().catalog());
    }
    @Test void sameIdentityCanKeepPasswordAndExplicitlyClearIt() {
        var s=service();
        assertEquals("old-secret",s.candidate(request("127.0.0.1","old_db","reader","",false,List.of())).password());
        assertEquals("",s.candidate(request("127.0.0.1","old_db","reader","",true,List.of())).password());
        assertThrows(ModelSettingsException.class,()->s.candidate(request("127.0.0.1","old_db","reader","x",true,List.of())));
    }
    @Test void targetOrAccountChangeCannotReuseExistingPassword() {
        var s=service();
        for (var r:List.of(request("other","old_db","reader","",false,List.of()),request("127.0.0.1","new_db","reader","",false,List.of()),
                request("127.0.0.1","old_db","other","",false,List.of()),new DatabaseSettingsRequest("127.0.0.1",3307,"old_db","reader","",false,false,List.of()))) {
            assertEquals("DATABASE_PASSWORD_REQUIRED",assertThrows(ModelSettingsException.class,()->s.test(r)).code());
        }
    }
    @Test void changingCandidateDoesNotRequireOrCreatePersistence() {
        var s=service(); s.test(request("other","new_db","reader","secret",false,List.of()));
        assertNull(s.view().saved()); assertFalse(Files.exists(dir.resolve("database.properties")));
    }
    @Test void everySaveRetestsExactSubmittedConnectionAndTables() {
        var count=new AtomicInteger();
        var s=new DatabaseSettingsService(active,dir.resolve("database.properties"),c -> { assertEquals(List.of("shop"),c.tables()); assertEquals("new_db",c.catalog()); count.incrementAndGet(); return tester.inspect(c); });
        var r=request("other","new_db","reader","secret",false,List.of("shop"));
        s.test(r); s.save(r); assertEquals(2,count.get());
    }
    @Test void failedTestKeepsPreviousSavedFileAndSnapshot() throws Exception {
        var s=service(); s.save(request("other","new_db","reader","secret",false,List.of("shop")));
        byte[] before=Files.readAllBytes(dir.resolve("database.properties")); var view=s.view();
        var fail=new DatabaseSettingsService(active,dir.resolve("database.properties"),c -> { throw new ModelSettingsException(503,"FAILED","safe"); });
        assertThrows(ModelSettingsException.class,()->fail.save(request("another","new_db","reader","changed",false,List.of("shop"))));
        assertEquals(view,fail.view()); assertArrayEquals(before,Files.readAllBytes(dir.resolve("database.properties")));
    }
    @Test void failedAtomicSaveKeepsMemoryAndCleansTemporaryFiles() throws Exception {
        // An absent path is needed for construction; create the blocking target afterward.
        Path target=dir.resolve("config.properties"); var s=new DatabaseSettingsService(active,target,tester);
        Files.createDirectory(target); Files.writeString(target.resolve("keep"),"data");
        assertEquals("DATABASE_SAVE_FAILED",assertThrows(ModelSettingsException.class,()->s.save(request("other","new_db","reader","secret",false,List.of("shop")))).code());
        assertNull(s.view().saved()); assertEquals("data",Files.readString(target.resolve("keep")));
        try (var paths=Files.list(dir)) { assertFalse(paths.anyMatch(p->p.getFileName().toString().startsWith(".database-"))); }
    }
    @ParameterizedTest @ValueSource(strings={"EXTRA_PRIVILEGES","UNKNOWN"})
    void elevatedOrUnconfirmedAccountCannotBeSaved(String privileges) {
        var s=new DatabaseSettingsService(active,dir.resolve("db"),c -> new DatabaseConnectionTester.Result("root@localhost",c.catalog(),privileges,List.of("shop"),false,List.of()));
        assertEquals("DATABASE_READONLY_REQUIRED",assertThrows(ModelSettingsException.class,()->s.save(request("other","new_db","reader","secret",false,List.of("shop")))).code());
        assertNull(s.view().saved()); assertFalse(Files.exists(dir.resolve("db")));
    }
    @Test void emptySelectionCannotBeSaved() { assertThrows(ModelSettingsException.class,()->service().save(request("other","new_db","reader","secret",false,List.of()))); }
    @Test void pendingPasswordIsTheBaseForLaterEditing() {
        var s=service(); s.save(request("other","new_db","reader","new-secret",false,List.of("shop")));
        assertEquals("new-secret",s.candidate(request("other","new_db","reader","",false,List.of())).password());
    }
    @ParameterizedTest @ValueSource(strings={"host/other","host?option=x","host:3307","host@other","host%20"," host","host\n"})
    void arbitraryJdbcSyntaxIsRejected(String host) { assertThrows(ModelSettingsException.class,()->service().test(request(host,"new_db","reader","secret",false,List.of()))); }
    @Test void unsafeTableAndPasswordAndBoundsAreRejected() {
        var s=service();
        for (var tables:List.of(List.of("shop;DROP"),List.of("shop","shop"),java.util.stream.IntStream.range(0,51).mapToObj(i->"t"+i).toList()))
            assertThrows(ModelSettingsException.class,()->s.test(request("host","db","reader","secret",false,tables)));
        assertThrows(ModelSettingsException.class,()->s.test(request("host","db","reader","secret\n",false,List.of())));
        assertThrows(ModelSettingsException.class,()->s.test(new DatabaseSettingsRequest("host",65536,"db","reader","x",false,false,List.of())));
    }
    @Test void concurrentSavesAreSerializedAndPublishWholeSnapshots() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var count=new AtomicInteger(); var s=new DatabaseSettingsService(active,dir.resolve("database.properties"),c->{
            if (count.incrementAndGet()==1) { entered.countDown(); try { assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
            return tester.inspect(c);
        });
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->s.save(request("first","first_db","reader","first-secret",false,List.of("shop"))));
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var second=pool.submit(()->s.save(request("second","second_db","reader","second-secret",false,List.of("shop"))));
            assertFalse(Files.exists(dir.resolve("database.properties"))); release.countDown();
            assertEquals("first_db",first.get(5,java.util.concurrent.TimeUnit.SECONDS).saved().catalog()); second.get(5,java.util.concurrent.TimeUnit.SECONDS);
            var file=DatabaseConfigFile.read(dir.resolve("database.properties"));
            assertEquals("second_db",file.catalog()); assertEquals("second-secret",file.password());
            assertEquals(file.view(),s.view().saved()); assertEquals(active.view(),s.view().active());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void startupViewUsesActualHikariOverridesAndPreservesRequiredTls() {
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("spring.datasource.url","jdbc:mysql://wrong/wrong_db")
                .withProperty("spring.datasource.username","wrong").withProperty("spring.datasource.password","wrong-secret")
                .withProperty("spring.datasource.hikari.jdbc-url","jdbc:mysql://db.example:3307/db?sslMode=VERIFY_CA")
                .withProperty("spring.datasource.hikari.username","reader").withProperty("spring.datasource.hikari.password","correct-secret");
        var meta=new com.sqlfinder.datasource.config.MetadataProperties(); meta.setCatalog("db"); meta.setAllowedTables(List.of("shop"));
        var s=new DatabaseSettingsService(DatabaseSettingsService.startup(env,meta),dir.resolve("database.properties"),tester);
        assertEquals("db.example",s.view().active().host()); assertEquals(3307,s.view().active().port()); assertTrue(s.view().active().requireTls());
        assertEquals("correct-secret",s.candidate(new DatabaseSettingsRequest("db.example",3307,"db","reader","",false,true,List.of())).password());
    }
    @Test void unsupportedStartupConnectionRequiresExplicitCandidatePassword() {
        var s=new DatabaseSettingsService(null,dir.resolve("database.properties"),tester);
        assertNull(s.view().active());
        assertEquals("DATABASE_PASSWORD_REQUIRED",assertThrows(ModelSettingsException.class,()->s.test(request("host","db","reader","",false,List.of()))).code());
        assertNotNull(s.test(request("host","db","reader","",true,List.of())));
    }
}
