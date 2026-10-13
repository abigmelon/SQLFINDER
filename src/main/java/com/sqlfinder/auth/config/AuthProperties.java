package com.sqlfinder.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 登录账号配置 */
@ConfigurationProperties(prefix = "sqlfinder.auth")
public record AuthProperties(List<Account> users) {
    public AuthProperties {
        users = users == null ? List.of() : List.copyOf(users);
    }

    public record Account(
            String username,
            String passwordHash,
            @DefaultValue("true") boolean enabled,
            List<String> allowedTables,
            @DefaultValue("false") boolean settingsAdmin) {

        @ConstructorBinding
        public Account {
            allowedTables = allowedTables == null
                    ? List.of()
                    : List.copyOf(allowedTables);
        }

        // 保留原有构造方式，兼容现有测试。
        public Account(String username, String passwordHash, boolean enabled) {
            this(username, passwordHash, enabled, List.of(), false);
        }

        public Account(String username, String passwordHash, boolean enabled, List<String> allowedTables) {
            this(username, passwordHash, enabled, allowedTables, false);
        }

        @Override
        public String toString() {
            return "Account[username=" + username
                    + ", passwordHash=[REDACTED]"
                    + ", enabled=" + enabled
                    + ", allowedTables=" + allowedTables + "]";
        }
    }

    public static boolean validUsername(String username) {
        return username != null && username.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    }

    public void validateAccounts() {
        if (users.isEmpty()) {
            throw new IllegalStateException("未配置登录账号；请先运行 --configure-login，或配置 sqlfinder.auth.users。");
        }
        Set<String> names = new HashSet<>();
        boolean enabledAccount = false;
        for (Account account : users) {
            if (!validUsername(account.username())) {
                throw new IllegalStateException("登录用户名应为 1～64 位字母、数字、点、下划线或短横线，且以字母或数字开头。");
            }
            // InMemoryUserDetailsManager 按不区分大小写的用户名查找，重复配置必须提前拒绝。
            if (!names.add(account.username().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("登录账号配置包含重复用户名（不区分大小写）。");
            }
            if (account.passwordHash() == null || !account.passwordHash().matches(
                    "\\$2[aby]\\$(0[4-9]|1[0-6])\\$[./A-Za-z0-9]{53}")) {
                // 错误中不拼接配置值，防止把误填的明文密码带入启动日志。
                throw new IllegalStateException("登录密码必须配置为完整的 BCrypt 哈希，工作因子范围为 4～16；不能填写明文密码。");
            }
            enabledAccount |= account.enabled();
            for (String table : account.allowedTables()) {
                if (!validTableGrant(table)) {
                    throw new IllegalStateException("账号表授权必须为 数据库名.表名，仅支持字母、数字和下划线，不支持通配符。");
                }
            }
        }
        if (!enabledAccount) throw new IllegalStateException("至少需要一个已启用的登录账号。");
    }

    public static boolean validTableGrant(String table) {
        return table != null && table.matches("[A-Za-z_][A-Za-z0-9_]{0,63}\\.[A-Za-z_][A-Za-z0-9_]{0,63}");
    }
}
