package com.sqlfinder.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.common.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 登录安全配置
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper mapper) throws Exception {

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/styles.css",
                                "/app.js",
                                "/auth.js",
                                "/login.html",
                                "/login.js",
                                "/health",
                                "/error",
                                "/api/auth/csrf"
                        ).permitAll()
                        .anyRequest().authenticated()
                )

                // 保留 CSRF 保护；前端提交时携带 token。
                .csrf(Customizer.withDefaults())

                // API 未登录时返回 JSON，不保存待跳转的 API 请求。
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(
                                        mapper, response, 401,
                                        "UNAUTHENTICATED",
                                        "请先登录。"
                                )
                        )
                        .accessDeniedHandler((request, response, ex) -> {
                            boolean csrfFailure = ex instanceof CsrfException;

                            writeError(
                                    mapper, response, 403,
                                    csrfFailure
                                            ? "CSRF_INVALID"
                                            : "ACCESS_DENIED",
                                    csrfFailure
                                            ? "安全校验失败，请刷新页面后重试。"
                                            : "你没有访问该资源的权限。"
                            );
                        })
                )

                .formLogin(login -> login
                        .loginPage("/login.html")
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) ->
                                writeJson(
                                        mapper, response, 200,
                                        Map.of("username", authentication.getName())
                                )
                        )
                        .failureHandler((request, response, ex) ->
                                writeError(
                                        mapper, response, 401,
                                        "AUTHENTICATION_FAILED",
                                        "用户名或密码不正确。"
                                )
                        )
                        .permitAll()
                )

                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                writeJson(
                                        mapper, response, 200,
                                        Map.of("message", "已退出登录。")
                                )
                        )
                );

        return http.build();
    }

    private void writeError(
            ObjectMapper mapper,
            HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {

        ApiErrorResponse body = new ApiErrorResponse(
                code,
                message,
                UUID.randomUUID().toString()
        );

        writeJson(mapper, response, status, body);
    }

    private void writeJson(
            ObjectMapper mapper,
            HttpServletResponse response,
            int status,
            Object body) throws IOException {

        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        mapper.writeValue(response.getWriter(), body);
    }
}
