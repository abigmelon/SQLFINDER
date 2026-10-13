package com.sqlfinder.auth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** 登录配置测试 */
class AuthUserConfigurationTest {
    private static final String PASSWORD = "private-test-password";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(AuthUserConfiguration.class);

    private WebApplicationContextRunner account(String username, String hash) {
        return runner.withPropertyValues("sqlfinder.auth.users[0].username=" + username,
                "sqlfinder.auth.users[0].password-hash=" + hash);
    }

    @Test void missingAccountsRejectWebStartupWithoutDefaultUserFallback() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "未配置登录账号；请先运行 --configure-login，或配置 sqlfinder.auth.users。");
        });
    }
    @Test void settingsAdminIsExplicitAndDefaultsToFalse() {
        account("admin", HASH).withPropertyValues("sqlfinder.auth.users[0].settings-admin=true").run(context -> {
            assertThat(context).hasNotFailed();
            var user=context.getBean(UserDetailsService.class).loadUserByUsername("admin");
            assertTrue(user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SETTINGS_ADMIN")));
            assertTrue(context.getBean(AuthProperties.class).users().get(0).settingsAdmin());
        });
    }
    @Test void configuredAccountsDefaultToEnabledAndOnlyHaveUserRole() {
        account("reader", HASH).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(UserDetailsService.class).hasSingleBean(PasswordEncoder.class);
            var user = context.getBean(UserDetailsService.class).loadUserByUsername("READER");
            assertEquals("reader", user.getUsername());
            assertTrue(user.isEnabled());
            assertEquals(List.of("ROLE_USER"), user.getAuthorities().stream().map(Object::toString).toList());
            assertTrue(context.getBean(PasswordEncoder.class).matches(PASSWORD, user.getPassword()));
            assertFalse(context.getBean(PasswordEncoder.class).matches("incorrect", user.getPassword()));
            assertThrows(UsernameNotFoundException.class,
                    () -> context.getBean(UserDetailsService.class).loadUserByUsername("user"));
        });
    }
    @Test void multipleAccountsHaveDistinctPasswordsAndEnabledFlags() {
        account("reader", HASH).withPropertyValues("sqlfinder.auth.users[1].username=disabled-reader",
                "sqlfinder.auth.users[1].password-hash=" + new BCryptPasswordEncoder(4).encode("different-test-password"),
                "sqlfinder.auth.users[1].enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            var disabled = context.getBean(UserDetailsService.class).loadUserByUsername("disabled-reader");
            assertFalse(disabled.isEnabled());
            assertFalse(context.getBean(PasswordEncoder.class).matches(PASSWORD, disabled.getPassword()));
        });
    }
    @Test void accountConfigurationIsNotRequiredForNonWebJdbcApplications() {
        new ApplicationContextRunner().withUserConfiguration(AuthUserConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(UserDetailsService.class).doesNotHaveBean(PasswordEncoder.class);
        });
    }
    @ParameterizedTest @ValueSource(strings = {"", "has space", " reader", "reader ", ".reader", "name@host",
            "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmn"})
    void invalidUsernamesPreventStartup(String username) {
        // TestPropertyValues 会自动去掉两端空白；整个账号使用同一原始属性源，避免 List 的覆盖规则。
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("raw-account-config", Map.of("sqlfinder.auth.users[0].username", username,
                        "sqlfinder.auth.users[0].password-hash", HASH)))).run(context -> assertThat(context).hasFailed());
    }
    @Test void missingUsernamePreventStartup() {
        runner.withPropertyValues("sqlfinder.auth.users[0].password-hash=" + HASH)
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void duplicateNamesAreRejectedCaseInsensitively() {
        account("reader", HASH).withPropertyValues("sqlfinder.auth.users[1].username=READER",
                "sqlfinder.auth.users[1].password-hash=" + HASH).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("登录账号配置包含重复用户名（不区分大小写）。");
        });
    }
    @ParameterizedTest @ValueSource(strings = {"", "private-test-password", "{noop}private-test-password",
            "{bcrypt}not-a-hash", "$2a$12$short", "$2x$12$unsupported"})
    void invalidPasswordsPreventStartupWithoutEchoingValues(String hash) {
        account("reader", hash).run(context -> {
            assertThat(context).hasFailed();
            assertFalse(context.getStartupFailure().toString().contains(PASSWORD));
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "登录密码必须配置为完整的 BCrypt 哈希，工作因子范围为 4～16；不能填写明文密码。");
        });
    }
    @Test void missingPasswordHashIsRejected() {
        runner.withPropertyValues("sqlfinder.auth.users[0].username=reader")
                .run(context -> assertThat(context).hasFailed());
    }
    @ParameterizedTest @ValueSource(strings = {"03", "17", "32"})
    void unsupportedBcryptCostsAreRejected(String cost) {
        account("reader", HASH.substring(0, 4) + cost + HASH.substring(6))
                .run(context -> assertThat(context).hasFailed());
    }
    @ParameterizedTest @ValueSource(strings = {"a", "b", "y"})
    void supportedBcryptVersionsMatchTheSamePassword(String version) {
        String hash = "$2" + version + HASH.substring(3);
        account("reader", hash).run(context -> {
            assertThat(context).hasNotFailed();
            assertTrue(context.getBean(PasswordEncoder.class).matches(PASSWORD, hash));
        });
    }
    @Test void disablingEveryAccountPreventsStartup() {
        account("reader", HASH).withPropertyValues("sqlfinder.auth.users[0].enabled=false").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("至少需要一个已启用的登录账号。");
        });
    }
    @Test void newHashesUseCost12AndRandomSalt() {
        account("reader", HASH).run(context -> {
            var encoder = context.getBean(PasswordEncoder.class);
            String first = encoder.encode(PASSWORD), second = encoder.encode(PASSWORD);
            assertTrue(first.startsWith("$2a$12$"));
            assertNotEquals(first, second);
            assertTrue(encoder.matches(PASSWORD, first));
        });
    }
    @Test void configurationStringRepresentationsRedactHashes() {
        var properties = new AuthProperties(List.of(new AuthProperties.Account("reader", HASH, true)));
        assertFalse(properties.toString().contains(HASH));
        assertFalse(properties.toString().contains(PASSWORD));
        assertTrue(properties.toString().contains("[REDACTED]"));
    }
    @Test void passwordBeyond72BytesCannotMatchByTruncatingSuffix() {
        String password = "a".repeat(72);
        String hash = new BCryptPasswordEncoder(4).encode(password);
        account("reader", hash).run(context -> {
            var encoder = context.getBean(PasswordEncoder.class);
            assertTrue(encoder.matches(password, hash));
            assertFalse(encoder.matches(password + "extra-suffix", hash));
        });
    }
    @Test void unicodePasswordLimitUsesUtf8BytesAndEncodingCannotTruncate() {
        account("reader", HASH).run(context -> {
            var encoder = context.getBean(PasswordEncoder.class);
            String password = "中".repeat(24);
            String hash = new BCryptPasswordEncoder(4).encode(password);
            assertTrue(encoder.matches(password, hash));
            assertFalse(encoder.matches(password + "中", hash));
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(password + "中"));
            assertFalse(encoder.matches(null, hash));
        });
    }

    @Test void tableGrantsBindThroughTheCanonicalAccountConstructor() {
        account("reader", HASH).withPropertyValues(
                "sqlfinder.auth.users[0].allowed-tables[0]=mybatis.tb_shop",
                "sqlfinder.auth.users[0].allowed-tables[1]=mybatis.tb_shop_type").run(context -> {
            assertThat(context).hasNotFailed();
            assertEquals(List.of("mybatis.tb_shop", "mybatis.tb_shop_type"),
                    context.getBean(AuthProperties.class).users().get(0).allowedTables());
        });
    }
    @ParameterizedTest @ValueSource(strings = {"tb_shop", "mybatis.*", "*.tb_shop", "mybatis.tb_shop.extra",
            "mybatis.tb-shop", "mybatis.`tb_shop`", "mybatis.tb_shop;DELETE", "mybatis.tb shop"})
    void malformedTableGrantsPreventStartup(String grant) {
        account("reader", HASH).withPropertyValues("sqlfinder.auth.users[0].allowed-tables[0]=" + grant)
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void accountWithoutGrantsCanLoginButReceivesNoImplicitTables() {
        account("reader", HASH).run(context -> {
            assertThat(context).hasNotFailed();
            assertTrue(context.getBean(AuthProperties.class).users().get(0).allowedTables().isEmpty());
        });
    }
}
