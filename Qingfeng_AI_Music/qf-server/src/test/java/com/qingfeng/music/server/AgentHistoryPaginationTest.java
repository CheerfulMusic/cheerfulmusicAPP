package com.qingfeng.music.server;

import com.qingfeng.music.conversation.api.*;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.infrastructure.persistence.agent.*;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentHistoryPaginationTest {
    @Test void sqlPagesHaveStableBoundariesOwnerChecksAndResetEpoch() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:history-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE agent_conversation(id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36) UNIQUE,current_work_id VARCHAR(36),created_at TIMESTAMP,updated_at TIMESTAMP,history_epoch BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE agent_message(id VARCHAR(36) PRIMARY KEY,conversation_id VARCHAR(36),seq BIGINT,role VARCHAR(16),kind VARCHAR(16),state VARCHAR(16),content_json TEXT,task_id VARCHAR(36),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE agent_conversation_state(conversation_id VARCHAR(36) PRIMARY KEY,current_song_json TEXT,revision BIGINT,updated_at TIMESTAMP)");
        var config=new Configuration(new org.apache.ibatis.mapping.Environment("test",new JdbcTransactionFactory(),ds));config.setMapUnderscoreToCamelCase(true);config.addMapper(ConversationMapper.class);
        try(var session=new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            var service=new ConversationService(new JdbcConversationRepository(session.getMapper(ConversationMapper.class)));
            String id=service.current("owner").id();service.current("other");
            for(int i=1;i<=95;i++)service.appendMessage("owner",id,i%2==0?"assistant":"user","TEXT","DONE","{\"text\":\"消息"+i+"\"}",null);
            var first=service.historyPage("owner",id,null,40);assertEquals(56,first.oldestSeq());assertEquals(95,first.latestSeq());assertTrue(first.hasOlderMessages());
            var second=service.historyPage("owner",id,first.oldestSeq(),40);assertEquals(16,second.oldestSeq());assertEquals(55,second.latestSeq());
            var third=service.historyPage("owner",id,second.oldestSeq(),40);assertEquals(15,third.messages().size());assertFalse(third.hasOlderMessages());
            assertEquals(List.of(94L,95L),service.listMessagesAfter("owner",id,93,100).stream().map(AgentMessage::seq).toList());
            assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->service.historyPage("other",id,null,40));
            service.reset("owner",id);assertEquals(1,service.historyEpoch("owner",id));assertTrue(service.historyPage("owner",id,null,40).messages().isEmpty());
        }
    }
}
