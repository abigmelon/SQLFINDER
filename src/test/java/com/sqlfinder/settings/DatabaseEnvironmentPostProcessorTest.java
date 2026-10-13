package com.sqlfinder.settings;

import com.sqlfinder.datasource.config.MetadataProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.boot.env.EnvironmentPostProcessor;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 数据库启动配置测试 */

class DatabaseEnvironmentPostProcessorTest {
    @TempDir Path dir;
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(MetadataProperties.class) static class Binding { }
    @Test void savedConfigOverridesEarlierSourcesAndBindsPoolAndMetadataTogetherWithoutConnecting() {
        var saved=new DatabaseConfiguration("db.example",3307,"saved_db","reader","hidden",true,List.of("new_table"));
        Path file=dir.resolve("db.properties"); DatabaseConfigFile.write(file,saved);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class)).withUserConfiguration(Binding.class)
                .withPropertyValues("spring.datasource.url=jdbc:mysql://oldhost/old_db","spring.datasource.username=root",
                        "spring.datasource.hikari.jdbc-url=jdbc:mysql://oldhost/wrong_db","spring.datasource.hikari.username=root",
                        "sqlfinder.metadata.catalog=old_db","sqlfinder.metadata.allowed-tables[0]=old_table","sqlfinder.metadata.allowed-tables[1]=another_table")
                .withInitializer(ctx->new DatabaseEnvironmentPostProcessor(file).postProcessEnvironment(ctx.getEnvironment(),new SpringApplication()))
                .run(ctx->{
                    assertNull(ctx.getStartupFailure());
                    var pool=ctx.getBean(HikariDataSource.class); var metadata=ctx.getBean(MetadataProperties.class);
                    assertEquals(saved.jdbcUrl(),pool.getJdbcUrl()); assertEquals("reader",pool.getUsername()); assertEquals("hidden",pool.getPassword());
                    assertTrue(pool.isReadOnly()); assertEquals("saved_db",metadata.getCatalog()); assertEquals(List.of("new_table"),metadata.getAllowedTables());
                    assertNull(pool.getHikariPoolMXBean());
                });
    }
    @Test void missingSavedFilePreservesExistingConfiguration() {
        var env=new StandardEnvironment(); env.getPropertySources().addFirst(new MapPropertySource("old",Map.of("spring.datasource.url","existing")));
        new DatabaseEnvironmentPostProcessor(dir.resolve("absent")).postProcessEnvironment(env,new SpringApplication());
        assertEquals("existing",env.getProperty("spring.datasource.url"));
    }
    @Test void malformedFileFailsClosedWithoutLeakingSecretOrDriverCause() throws Exception {
        Path file=dir.resolve("db"); Files.writeString(file,"password=sensitive-secret\nport=sensitive-secret\n");
        var ex=assertThrows(IllegalStateException.class,()->new DatabaseEnvironmentPostProcessor(file).postProcessEnvironment(new StandardEnvironment(),new SpringApplication()));
        assertNull(ex.getCause()); assertFalse(ex.toString().contains("sensitive-secret"));
    }
    @Test void productionProcessorIsRegistered() {
        assertTrue(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class,getClass().getClassLoader()).contains(DatabaseEnvironmentPostProcessor.class.getName()));
    }
    @Test void fixedJdbcOptionsBlockMultipleStatementsAndLocalFileAccess() {
        var c=new DatabaseConfiguration("localhost",3306,"db","reader","secret",false,List.of("t"));
        assertTrue(c.jdbcUrl().contains("connectTimeout=5000&socketTimeout=5000"));
        assertTrue(c.jdbcUrl().contains("allowMultiQueries=false&allowLoadLocalInfile=false&autoDeserialize=false"));
        assertFalse(c.jdbcUrl().contains("secret"));
    }
}
