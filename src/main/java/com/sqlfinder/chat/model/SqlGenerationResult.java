package com.sqlfinder.chat.model;

public record SqlGenerationResult(
        boolean success,
        String sql,
        String message
) {
}
