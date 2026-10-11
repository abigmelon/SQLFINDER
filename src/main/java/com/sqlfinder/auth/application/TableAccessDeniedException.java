package com.sqlfinder.auth.application;

/** 表授权拒绝：不暴露账号配置、数据库结构或凭据。 */
public class TableAccessDeniedException extends RuntimeException {
    public TableAccessDeniedException() {
        super("当前账号没有可用的数据表授权，请联系管理员配置。");
    }
}
