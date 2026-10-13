package com.sqlfinder.datasource.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** 表结构读取配置 */

@ConfigurationProperties(prefix = "sqlfinder.metadata")
public class MetadataProperties {

    private String catalog;
    private List<String> allowedTables = new ArrayList<>();

    public String getCatalog() {
        return catalog;
    }

    public void setCatalog(String catalog) {
        this.catalog = catalog;
    }

    public List<String> getAllowedTables() {
        return allowedTables;
    }

    public void setAllowedTables(List<String> allowedTables) {
        this.allowedTables = allowedTables;
    }
}
