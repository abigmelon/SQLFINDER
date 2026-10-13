package com.sqlfinder.chat.application;

/** 历史存储异常 */
public class HistoryStorageException extends RuntimeException {
    public HistoryStorageException(Throwable cause) {
        super("问数历史暂时无法读写，请检查本地数据目录。", cause);
    }
}
