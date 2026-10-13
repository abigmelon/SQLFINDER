package com.sqlfinder.auth.config;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 登录密码编码 */
public final class LoginPasswordEncoder extends BCryptPasswordEncoder {
    public LoginPasswordEncoder() { super(12); }

    private boolean withinByteLimit(CharSequence password) {
        if (password == null || password.length() > 72) return false;
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        int length = bytes.remaining();
        if (bytes.hasArray()) Arrays.fill(bytes.array(), (byte) 0);
        return length <= 72;
    }
    @Override public String encode(CharSequence rawPassword) {
        if (!withinByteLimit(rawPassword)) throw new IllegalArgumentException("密码 UTF-8 编码后不能超过 72 字节。");
        return super.encode(rawPassword);
    }
    @Override public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return withinByteLimit(rawPassword) && super.matches(rawPassword, encodedPassword);
    }
}
