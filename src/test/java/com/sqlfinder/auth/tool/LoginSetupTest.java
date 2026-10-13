package com.sqlfinder.auth.tool;

import com.sqlfinder.auth.config.AuthProperties;
import com.sqlfinder.auth.config.AuthUserConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** 账号初始化测试 */
class LoginSetupTest {
    @TempDir Path directory;
    private Path config() { return directory.resolve("config/auth.properties"); }
    private void cleared(char[] password) { assertTrue(Arrays.equals(new char[password.length], password)); }

    @ParameterizedTest @ValueSource(strings = {"password-1234", "abcdefghijkl"})
    void writesBcryptOnlyAndConfigBindsToProductionProperties(String secret) throws Exception {
        char[] password = secret.toCharArray(), confirmation = secret.toCharArray();
        LoginSetup.writeAccount(config(), "reader", password, confirmation);
        cleared(password); cleared(confirmation);
        String content = Files.readString(config());
        assertFalse(content.contains(secret));
        Properties stored = new Properties();
        try (var reader = Files.newBufferedReader(config(), StandardCharsets.UTF_8)) { stored.load(reader); }
        Map<String, Object> values = new LinkedHashMap<>();
        stored.forEach((key, value) -> values.put(key.toString(), value));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("local-login", values));
        AuthProperties accounts = Binder.get(environment).bind("sqlfinder.auth", AuthProperties.class)
                .orElseThrow(IllegalStateException::new);
        accounts.validateAccounts();
        assertEquals("reader", accounts.users().get(0).username());
        String hash = accounts.users().get(0).passwordHash();
        assertTrue(hash.startsWith("$2a$12$"));
        assertTrue(new BCryptPasswordEncoder().matches(secret, hash));
        assertTrue(accounts.users().get(0).enabled());
        assertTrue(accounts.users().get(0).settingsAdmin());
        // 验证实际 Spring Config Data 文件导入，而不仅是手工构造 Binder。
        new WebApplicationContextRunner().withUserConfiguration(AuthUserConfiguration.class)
                .withPropertyValues("spring.profiles.active=test", "spring.config.import=" + config().toUri())
                .withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
                    assertNull(context.getStartupFailure());
                    var user = context.getBean(UserDetailsService.class).loadUserByUsername("reader");
                    assertTrue(context.getBean(PasswordEncoder.class).matches(secret, user.getPassword()));
                });
    }
    @ParameterizedTest @ValueSource(strings = {"12345678901", "中中中中中中中中中中中中中中中中中中中中中中中中中"})
    void rejectsTooShortOrTooManyUtf8Bytes(String secret) {
        char[] password = secret.toCharArray(), confirmation = secret.toCharArray();
        assertThrows(IllegalArgumentException.class, () -> LoginSetup.writeAccount(config(), "reader", password, confirmation));
        assertFalse(Files.exists(config()));
        cleared(password); cleared(confirmation);
    }
    @Test void acceptsExactly72Utf8BytesWithoutTruncating() throws Exception {
        String secret = "中".repeat(24);
        LoginSetup.writeAccount(config(), "reader", secret.toCharArray(), secret.toCharArray());
        Properties stored = new Properties();
        try (var reader = Files.newBufferedReader(config())) { stored.load(reader); }
        assertTrue(new BCryptPasswordEncoder().matches(secret, stored.getProperty("sqlfinder.auth.users[0].password-hash")));
    }
    @Test void existingConfigIsNotOverwrittenAndPasswordIsCleared() throws Exception {
        Files.createDirectories(config().getParent());
        Files.writeString(config(), "existing-account-marker");
        char[] password = "password-1234".toCharArray(), confirmation = "password-1234".toCharArray();
        assertThrows(IllegalStateException.class, () -> LoginSetup.writeAccount(config(), "reader", password, confirmation));
        assertEquals("existing-account-marker", Files.readString(config()));
        cleared(password); cleared(confirmation);
    }
    @Test void mismatchedConfirmationDoesNotWriteConfig() {
        char[] password = "password-1234".toCharArray(), confirmation = "password-5678".toCharArray();
        assertThrows(IllegalArgumentException.class, () -> LoginSetup.writeAccount(config(), "reader", password, confirmation));
        assertFalse(Files.exists(config()));
        cleared(password); cleared(confirmation);
    }
    @Test void invalidUsernameDoesNotWriteConfig() {
        char[] password = "password-1234".toCharArray(), confirmation = "password-1234".toCharArray();
        assertThrows(IllegalArgumentException.class, () -> LoginSetup.writeAccount(config(), "user@host", password, confirmation));
        assertFalse(Files.exists(config()));
        cleared(password); cleared(confirmation);
    }
    @Test void cancellingPasswordInputDoesNotWriteConfig() {
        char[] confirmation = "password-1234".toCharArray();
        assertThrows(IllegalArgumentException.class, () -> LoginSetup.writeAccount(config(), "reader", null, confirmation));
        assertFalse(Files.exists(config()));
        cleared(confirmation);
    }
}
