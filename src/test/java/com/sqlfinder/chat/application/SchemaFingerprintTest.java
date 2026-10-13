package com.sqlfinder.chat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.datasource.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 数据源指纹测试 */
class SchemaFingerprintTest {
    @Test void identicalSchemasInDifferentDatabasesDoNotShareHistoryScope() {
        var schemas = List.of(new TableSchema("business", "shop", "商铺", List.of()));
        var first = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:mysql://host-a/business")
                .withProperty("spring.datasource.username", "reader");
        var second = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:mysql://host-b/business")
                .withProperty("spring.datasource.username", "reader");
        var mapper = new ObjectMapper();
        assertNotEquals(new SchemaFingerprint(mapper, first).of(schemas), new SchemaFingerprint(mapper, second).of(schemas));
        first.withProperty("spring.datasource.password", "changed-secret");
        var before = new SchemaFingerprint(mapper, first).of(schemas);
        first.withProperty("spring.datasource.password", "rotated-secret");
        assertEquals(before, new SchemaFingerprint(mapper, first).of(schemas));
        first.withProperty("spring.datasource.hikari.jdbc-url", "jdbc:mysql://host-b/business");
        assertEquals(new SchemaFingerprint(mapper, second).of(schemas), new SchemaFingerprint(mapper, first).of(schemas));
    }
}
