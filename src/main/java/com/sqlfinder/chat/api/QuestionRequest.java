package com.sqlfinder.chat.api;

import jakarta.validation.constraints.NotBlank;

public record QuestionRequest(
        @NotBlank(message = "question 不能为空") String question,
        Long chatId) {
}
