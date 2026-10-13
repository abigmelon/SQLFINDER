package com.sqlfinder.chat.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.application.ConversationStore;
import com.sqlfinder.chat.infrastructure.JdbcConversationRepository;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;

/** 历史测试存储 */
public final class ConversationTestStores {
    private ConversationTestStores() { }
    public static ConversationStore create() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        return new ConversationStore(new JdbcConversationRepository(source, new ObjectMapper()));
    }
}
