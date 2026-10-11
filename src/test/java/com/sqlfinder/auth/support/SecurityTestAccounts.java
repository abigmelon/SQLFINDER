package com.sqlfinder.auth.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import com.sqlfinder.auth.config.AuthUserConfiguration;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.Map;
import java.util.HashMap;

/** 专用测试配置：使用生产用户来源和 BCrypt 校验；不参与普通应用启动。 */
@TestConfiguration(proxyBeanMethods = false)
@Import(AuthUserConfiguration.class)
public class SecurityTestAccounts {
    public static final String USERNAME = "test-reader";
    public static final String PASSWORD = "test-only-password";

    public static final String SECOND_USERNAME = "second-reader";
    public static final String SECOND_PASSWORD = "second-test-password";
    public static final String DISABLED_USERNAME = "disabled-reader";
    public static final String LONG_PASSWORD_USERNAME = "long-reader";
    public static final String LONG_PASSWORD = "a".repeat(72);

    public static final String NO_GRANTS_USERNAME = "no-grants";

    @Bean
    static BeanFactoryPostProcessor testAccountProperties(ConfigurableEnvironment environment) {
        return factory -> {
            Map<String, Object> values = new HashMap<>();
            var encoder = new BCryptPasswordEncoder(4);
            String[] names = {USERNAME, SECOND_USERNAME, DISABLED_USERNAME, LONG_PASSWORD_USERNAME, NO_GRANTS_USERNAME};
            String[] passwords = {PASSWORD, SECOND_PASSWORD, PASSWORD, LONG_PASSWORD, PASSWORD};
            for (int i = 0; i < names.length; i++) {
                values.put("sqlfinder.auth.users[" + i + "].username", names[i]);
                values.put("sqlfinder.auth.users[" + i + "].password-hash", encoder.encode(passwords[i]));
            }
            values.put("sqlfinder.auth.users[2].enabled", "false");
            values.put("sqlfinder.auth.users[0].allowed-tables[0]", "mybatis.tb_shop");
            values.put("sqlfinder.auth.users[0].allowed-tables[1]", "mybatis.tb_shop_type");
            values.put("sqlfinder.auth.users[1].allowed-tables[0]", "mybatis.tb_shop_type");
            values.put("sqlfinder.auth.users[3].allowed-tables[0]", "mybatis.tb_shop");
            environment.getPropertySources().addFirst(new MapPropertySource("security-test-accounts", values));
        };
    }
}
