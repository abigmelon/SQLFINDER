package com.sqlfinder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import com.sqlfinder.auth.tool.LoginSetup;

@ConfigurationPropertiesScan
@SpringBootApplication
public class SqlFinderApplication {
    public static void main(String[] args) {
        // 独立初始化命令：不启动 Spring，也不加载数据库或模型配置。
        if (args.length == 1 && "--configure-login".equals(args[0])) {
            try {
                LoginSetup.run();
            } catch (IllegalArgumentException | IllegalStateException | java.io.IOException error) {
                System.err.println(error instanceof java.io.IOException
                        ? "无法写入登录配置，请检查目录及文件权限。" : error.getMessage());
                System.exit(1);
            }
            return;
        }
        SpringApplication.run(SqlFinderApplication.class, args);
    }
}
