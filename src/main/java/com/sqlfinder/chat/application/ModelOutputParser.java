package com.sqlfinder.chat.application;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.model.SqlGenerationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ModelOutputParser {

    private final ObjectMapper objectMapper;

    @Autowired
    public ModelOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SqlGenerationResult parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("模型回答不能为空");
        }

        JsonNode root;
        try {
            root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS) //拒绝后续的字符
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY) //拒绝重复的字段
                    .readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("模型回答不是有效的单个JSON对象", e);
        }

        if (root == null || !root.isObject()) {
            throw new IllegalStateException("模型回答必须是JSON对象"); //要求必须是带字段的对象
        }

        if (!root.has("success")
                || !root.has("sql")
                || !root.has("message")) {
            throw new IllegalStateException("模型回答缺少必要字段"); //要求三个字段都出现
        }

        JsonNode success = root.get("success");
        JsonNode sql = root.get("sql");
        JsonNode message = root.get("message");

        if (!success.isBoolean()) {
            throw new IllegalStateException("success必须是布尔值");
        }

        if (success.booleanValue()) {
            if (!sql.isTextual() || sql.textValue().isBlank()) {
                throw new IllegalStateException("生成成功时sql必须是非空字符串");
            }
            if (!message.isNull()) {
                throw new IllegalStateException("生成成功时message必须为 null");
            }

            return new SqlGenerationResult(true, sql.textValue(), null);
        }

        if (!sql.isNull()) {
            throw new IllegalStateException("无法生成时sql必须为null");
        }
        if (!message.isTextual() || message.textValue().isBlank()) {
            throw new IllegalStateException("无法生成时必须提供说明");
        }

        return new SqlGenerationResult(false, null, message.textValue());
    }
}
