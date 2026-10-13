package com.sqlfinder.common.application;

import java.util.Objects;

/** 问数处理异常 */
public class QuestionProcessingException extends RuntimeException {

    public enum Stage {
        METADATA,
        PROMPT,
        MODEL_REQUEST,
        MODEL_OUTPUT
    } // 枚举

    private final Stage stage;

    public QuestionProcessingException(Stage stage, Throwable cause) {
        super(Objects.requireNonNull(stage).name(), cause);
        this.stage = stage;
    }

    public Stage getStage() {
        return stage;
    }
}
