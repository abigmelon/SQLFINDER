package com.sqlfinder.auth.support;

import com.sqlfinder.auth.config.AuthProperties;
import com.sqlfinder.datasource.application.MetadataService;
import com.sqlfinder.datasource.application.UserMetadataService;
import com.sqlfinder.datasource.config.MetadataProperties;

import java.util.List;

/** 测试表授权配置 */
public final class TestTableAuthorization {
    private TestTableAuthorization() {}
    public static final List<String> TABLES = List.of("mybatis.tb_shop", "mybatis.tb_shop_type");
    public static UserMetadataService forMetadata(MetadataService metadata) {
        var global = new MetadataProperties();
        global.setCatalog("mybatis");
        global.setAllowedTables(List.of("tb_shop", "tb_shop_type"));
        return forMetadata(metadata, global);
    }
    public static UserMetadataService forMetadata(MetadataService metadata, MetadataProperties global) {
        var accounts = new AuthProperties(List.of(
                new AuthProperties.Account("test-reader", null, true, TABLES),
                new AuthProperties.Account("local-user", null, true, TABLES),
                new AuthProperties.Account("second-reader", null, true, List.of("mybatis.tb_shop_type")),
                new AuthProperties.Account("no-grants", null, true, List.of()),
                new AuthProperties.Account("disabled-reader", null, false, TABLES)));
        return new UserMetadataService(metadata, accounts, global);
    }
}
