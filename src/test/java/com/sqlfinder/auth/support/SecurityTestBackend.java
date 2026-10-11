package com.sqlfinder.auth.support;

import com.sqlfinder.chat.api.AnswerResponse;
import com.sqlfinder.chat.api.QuestionRequest;
import com.sqlfinder.chat.application.QuestionOrchestrator;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import com.sqlfinder.datasource.application.MetadataService;
import com.sqlfinder.datasource.application.UserMetadataService;
import com.sqlfinder.datasource.model.TableSchema;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 认证测试的业务替身：记录调用身份，绝不连接模型或数据库。 */
@TestConfiguration(proxyBeanMethods = false)
public class SecurityTestBackend {
    public static class RecordingOrchestrator extends QuestionOrchestrator {
        public final AtomicInteger calls = new AtomicInteger();
        public volatile String username;
        public RecordingOrchestrator() { super(null, null, null, null, null, null, null); }
        @Override
        public AnswerResponse ask(String userId, QuestionRequest request) {
            calls.incrementAndGet();
            username = userId;
            return new AnswerResponse("SQL_VALIDATED", request.question(), "SELECT COUNT(*) FROM tb_shop",
                    List.of(), List.of(), "测试 SQL 已校验，尚未执行。");
        }
        public void reset() { calls.set(0); username = null; }
    }

    @Bean RecordingOrchestrator testOrchestrator() { return new RecordingOrchestrator(); }
    @Bean QueryExecutionProperties testQueryProperties() { return new QueryExecutionProperties(false, 5, 1000); }
    @Bean UserMetadataService testUserMetadata(MetadataService metadata) { return TestTableAuthorization.forMetadata(metadata); }
    @Bean MetadataService testMetadata() { return () -> List.of(new TableSchema("mybatis", "tb_shop", "", List.of())); }
}
