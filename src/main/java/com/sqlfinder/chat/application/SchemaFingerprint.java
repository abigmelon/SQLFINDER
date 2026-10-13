package com.sqlfinder.chat.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.datasource.model.TableSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 授权结构指纹 */
@Component
public class SchemaFingerprint {
    private final ObjectMapper objectMapper;
    private final String sourceIdentity;

    public SchemaFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.sourceIdentity = "";
    }

    @Autowired
    public SchemaFingerprint(ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        this.sourceIdentity = environment.getProperty("spring.datasource.hikari.jdbc-url",
                environment.getProperty("spring.datasource.url", "")) + "\n"
                + environment.getProperty("spring.datasource.hikari.username",
                environment.getProperty("spring.datasource.username", ""));
    }

    public String of(List<TableSchema> schemas) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(java.util.Map.of("source", sourceIdentity, "schema", schemas));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("计算授权结构摘要失败", ex);
        }
    }
}
