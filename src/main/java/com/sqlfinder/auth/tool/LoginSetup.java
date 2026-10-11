package com.sqlfinder.auth.tool;

import com.sqlfinder.auth.config.AuthProperties;
import com.sqlfinder.auth.config.LoginPasswordEncoder;

import java.io.Console;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Properties;

/** 本机账号初始化：终端隐藏输入密码，只将 BCrypt 哈希写入未跟踪配置。 */
public final class LoginSetup {
    private LoginSetup() {}

    public static void run() throws IOException {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("请在本机交互终端运行 --configure-login；该命令需要隐藏密码输入。");
        }
        Path target = Path.of("config", "auth.properties").toAbsolutePath().normalize();
        if (Files.exists(target)) {
            throw new IllegalStateException("登录配置已存在；初始化不会覆盖已有账号。");
        }
        String username = console.readLine("登录用户名（回车使用 sqlfinder）：");
        if (username == null) throw new IllegalArgumentException("账号初始化已取消。");
        username = username.isBlank() ? "sqlfinder" : username.trim();
        char[] password = console.readPassword("设置密码（至少 12 个字符）：");
        char[] confirmation = null;
        try {
            confirmation = console.readPassword("再次输入密码：");
            writeAccount(target, username, password, confirmation);
            console.printf("已创建登录账号 %s，配置保存在 %s。现在可以正常启动服务。%n", username, target);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }

    static void writeAccount(Path target, String username, char[] password, char[] confirmation) throws IOException {
        try {
            if (!AuthProperties.validUsername(username)) throw new IllegalArgumentException("登录用户名格式不正确。");
            if (password == null || confirmation == null) throw new IllegalArgumentException("账号初始化已取消。");
            if (!Arrays.equals(password, confirmation)) throw new IllegalArgumentException("两次输入的密码不一致。");
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
            int byteLength = bytes.remaining();
            if (bytes.hasArray()) Arrays.fill(bytes.array(), (byte) 0);
            if (password.length < 12 || byteLength > 72) {
                throw new IllegalArgumentException("密码至少需要 12 个字符，UTF-8 编码后不能超过 72 字节。");
            }
            if (Files.exists(target)) throw new IllegalStateException("登录配置已存在；初始化不会覆盖已有账号。");
            String hash = new LoginPasswordEncoder().encode(CharBuffer.wrap(password));
            Properties properties = new Properties();
            properties.setProperty("sqlfinder.auth.users[0].username", username);
            properties.setProperty("sqlfinder.auth.users[0].password-hash", hash);
            properties.setProperty("sqlfinder.auth.users[0].enabled", "true");
            StringWriter content = new StringWriter();
            properties.store(content, "SQLFINDER local login; BCrypt hash only; keep this file private.");
            Path absolute = target.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            // CREATE_NEW 也防止在检查与写入之间出现的已有文件被覆盖。
            Files.writeString(absolute, content.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }
}
