package com.sqlfinder.common.api;

import com.sqlfinder.system.HealthController;
import com.sqlfinder.auth.config.SecurityConfig;
import com.sqlfinder.auth.support.SecurityTestAccounts;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 静态页面资源测试 */
@WebMvcTest(controllers = HealthController.class)
@Import({SecurityConfig.class, SecurityTestAccounts.class})
class StaticWorkbenchTest {
    @Autowired MockMvc http;

    @ParameterizedTest
    @CsvSource({"/index.html,text/html", "/styles.css,text/css", "/app.js,text/javascript",
            "/login.html,text/html", "/auth.js,text/javascript", "/login.js,text/javascript", "/chat.css,text/css",
            "/chart-data.js,text/javascript","/charts.js,text/javascript","/vendor/echarts.min.js,text/javascript"})
    void workbenchAssetsAreServedWithCorrectMediaType(String path, String mediaType) throws Exception {
        http.perform(get(path)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(mediaType));
    }
}
