package com.sqlfinder.auth.application;

/** 表授权拒绝异常 */
public class TableAccessDeniedException extends RuntimeException {
    public TableAccessDeniedException() {
        super("当前账号没有可用的数据表授权，请联系管理员配置。");
    }
}
