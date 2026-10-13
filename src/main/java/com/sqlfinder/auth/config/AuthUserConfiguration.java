package com.sqlfinder.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/** 登录用户来源 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthUserConfiguration {
    @Bean
    public PasswordEncoder passwordEncoder() { return new LoginPasswordEncoder(); }

    @Bean
    public UserDetailsService userDetailsService(AuthProperties properties) {
        properties.validateAccounts();
        var users = properties.users().stream().map(account -> User.withUsername(account.username())
                .password(account.passwordHash()).disabled(!account.enabled())
                .roles(account.settingsAdmin() ? new String[]{"USER", "SETTINGS_ADMIN"} : new String[]{"USER"})
                .build()).toList();
        return new InMemoryUserDetailsManager(users);
    }
}
