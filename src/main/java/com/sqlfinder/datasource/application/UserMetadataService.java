package com.sqlfinder.datasource.application;

import com.sqlfinder.auth.application.TableAccessDeniedException;
import com.sqlfinder.auth.config.AuthProperties;
import com.sqlfinder.datasource.config.MetadataProperties;
import com.sqlfinder.datasource.model.TableSchema;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 用户表授权 */
@Service
public class UserMetadataService {
    private final MetadataService metadata;
    private final AuthProperties accounts;
    private final MetadataProperties global;

    public UserMetadataService(MetadataService metadata, AuthProperties accounts, MetadataProperties global) {
        this.metadata = metadata;
        this.accounts = accounts;
        this.global = global;
    }

    public List<TableSchema> getAuthorizedSchemas(String username) {
        // 身份来自认证 Principal；禁止从问题 JSON 或候选 SQL 摘要提取授权。
        var account = accounts.users().stream()
                .filter(a -> username != null && a.username().equalsIgnoreCase(username) && a.enabled())
                .findFirst().orElseThrow(TableAccessDeniedException::new);
        Set<String> granted = new HashSet<>(account.allowedTables());
        Set<String> effective = new HashSet<>();
        for (String table : global.getAllowedTables()) {
            String qualified = global.getCatalog() + "." + table;
            if (granted.contains(qualified)) effective.add(qualified);
        }
        // 无授权时不读取数据库，也不允许后续调用模型。
        if (effective.isEmpty()) throw new TableAccessDeniedException();
        List<TableSchema> schemas = metadata.getAuthorizedSchemas().stream()
                .filter(table -> effective.contains(table.catalog() + "." + table.tableName()))
                .toList();
        if (schemas.isEmpty()) throw new TableAccessDeniedException();
        return schemas;
    }
}
