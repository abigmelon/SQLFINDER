package com.sqlfinder.chat.model;

/** SQL生成结果 */

public record SqlGenerationResult(
        boolean success,
        String sql,
        String message
) {
}
