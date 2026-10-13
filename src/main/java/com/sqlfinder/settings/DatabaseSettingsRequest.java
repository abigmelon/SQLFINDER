package com.sqlfinder.settings;

import java.util.List;

/** 数据库设置请求 */
public record DatabaseSettingsRequest(String host, Integer port, String catalog, String username,
                                      String password, boolean clearPassword, boolean requireTls, List<String> tables) {
    @Override public String toString() { return "DatabaseSettingsRequest[password=[REDACTED]]"; }
}
