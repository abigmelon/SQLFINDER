package com.sqlfinder.datasource.application;

import com.sqlfinder.auth.application.TableAccessDeniedException;
import com.sqlfinder.auth.config.AuthProperties;
import com.sqlfinder.datasource.config.MetadataProperties;
import com.sqlfinder.datasource.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sqlfinder.auth.support.TestTableAuthorization.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用户表权限回归：身份、全局交集、明确授权及结构泄漏边界，不连接数据库。 */
class UserMetadataServiceTest {
    private final TableSchema shop = new TableSchema("mybatis", "tb_shop", "", List.of());
    private final TableSchema type = new TableSchema("mybatis", "tb_shop_type", "", List.of());

    @Test void usersOnlyReceiveTheirOwnGrantedSchemas() {
        var service = forMetadata(() -> List.of(shop, type));
        assertEquals(List.of(shop, type), service.getAuthorizedSchemas("TEST-READER"));
        assertEquals(List.of(type), service.getAuthorizedSchemas("second-reader"));
        assertThrows(UnsupportedOperationException.class, () -> service.getAuthorizedSchemas("test-reader").clear());
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"unknown", "no-grants", "disabled-reader", " test-reader", "test-reader "})
    void unavailableIdentitiesNeverReadMetadata(String username) {
        AtomicInteger calls = new AtomicInteger();
        var service = forMetadata(() -> { calls.incrementAndGet(); return List.of(shop, type); });
        assertThrows(TableAccessDeniedException.class, () -> service.getAuthorizedSchemas(username));
        assertEquals(0, calls.get());
    }

    @Test void userGrantCannotExpandGlobalWhitelistOrExposeOtherCatalogs() {
        var global = new MetadataProperties();
        global.setCatalog("mybatis");
        global.setAllowedTables(List.of("tb_shop"));
        var service = forMetadata(() -> List.of(shop, type,
                new TableSchema("other_db", "tb_shop", "private", List.of())), global);
        assertEquals(List.of(shop), service.getAuthorizedSchemas("test-reader"));
        assertThrows(TableAccessDeniedException.class, () -> service.getAuthorizedSchemas("second-reader"));
    }

    @Test void globalGrantRevocationIsCheckedOnEveryRead() {
        var global = new MetadataProperties();
        global.setCatalog("mybatis");
        global.setAllowedTables(List.of("tb_shop", "tb_shop_type"));
        var service = forMetadata(() -> List.of(shop, type), global);
        assertEquals(2, service.getAuthorizedSchemas("test-reader").size());
        global.setAllowedTables(List.of("tb_shop_type"));
        assertEquals(List.of(type), service.getAuthorizedSchemas("test-reader"));
        global.setAllowedTables(List.of());
        assertThrows(TableAccessDeniedException.class, () -> service.getAuthorizedSchemas("test-reader"));
    }

    @Test void grantMustMatchDatabaseAndTableCaseExactly() {
        var global = new MetadataProperties();
        global.setCatalog("mybatis");
        global.setAllowedTables(List.of("tb_shop"));
        var accounts = new AuthProperties(List.of(new AuthProperties.Account("reader", null, true,
                List.of("other_db.tb_shop", "mybatis.TB_SHOP"))));
        var service = new UserMetadataService(() -> { throw new AssertionError("不能读取未授权结构"); }, accounts, global);
        assertThrows(TableAccessDeniedException.class, () -> service.getAuthorizedSchemas("reader"));
    }

    @Test void disappearingMetadataNeverProducesAnEmptyAuthorizedScope() {
        assertThrows(TableAccessDeniedException.class,
                () -> forMetadata(List::of).getAuthorizedSchemas("test-reader"));
    }
}
