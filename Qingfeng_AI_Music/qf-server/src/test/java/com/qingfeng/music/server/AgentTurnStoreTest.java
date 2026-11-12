package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.persistence.agent.JdbcAgentTurnStore;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;

/** Pure isolated H2 tests, no application bootstrap, provider calls or configured datasource. */
class AgentTurnStoreTest {
    JdbcTemplate jdbc;JdbcAgentTurnStore store;String key;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:agent-turn-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE agent_conversation(id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE agent_message(id VARCHAR(36) PRIMARY KEY,conversation_id VARCHAR(36),seq BIGINT,role VARCHAR(16),kind VARCHAR(16),state VARCHAR(16),content_json TEXT,created_at TIMESTAMP,UNIQUE(conversation_id,seq))");
        jdbc.update("INSERT INTO agent_conversation VALUES('conversation','owner')");
        store=new JdbcAgentTurnStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)));key=UUID.randomUUID().toString();
    }
    @Test void replayRemainsDeduplicatedAfterReconstructingStore(){
        assertTrue(store.claim("owner","conversation",key,"生成音乐").fresh());
        store.complete("owner","conversation",key,"{\"action\":\"generate_song\"}");
        var replay=new JdbcAgentTurnStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))).claim("owner","conversation",key,"生成音乐");
        assertFalse(replay.fresh());assertTrue(replay.resultJson().contains("generate_song"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_message",Integer.class));
    }
    @Test void sameKeyWithDifferentInputCannotExecute(){
        store.claim("owner","conversation",key,"原话");
        var error=assertThrows(DomainException.class,()->store.claim("owner","conversation",key,"另一个要求"));
        assertEquals("REQUEST_KEY_CONFLICT",error.code());
    }
    @Test void inFlightRequestCannotRunTwiceOrInParallel(){
        store.claim("owner","conversation",key,"生成音乐");
        assertEquals("AGENT_TURN_IN_PROGRESS",assertThrows(DomainException.class,()->store.claim("owner","conversation",key,"生成音乐")).code());
        assertEquals("AGENT_TURN_IN_PROGRESS",assertThrows(DomainException.class,()->store.claim("owner","conversation",UUID.randomUUID().toString(),"再生成")).code());
    }
    @Test void otherCreatorCannotReadOrClaimConversation(){
        assertEquals("AGENT_CONVERSATION_NOT_FOUND",assertThrows(DomainException.class,()->store.claim("stranger","conversation",key,"你好")).code());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_message",Integer.class));
    }
    @Test void resetTombstonePreservesDedupAndOldTurnCannotComplete(){
        store.claim("owner","conversation",key,"生成音乐");
        store.complete("owner","conversation",key,"{\"action\":\"generate_song\"}");
        jdbc.update("UPDATE agent_message SET role='system',kind='REQUEST'");
        assertFalse(store.claim("owner","conversation",key,"生成音乐").fresh());
        assertEquals("AGENT_TURN_RESET_CONFLICT",assertThrows(DomainException.class,()->store.assertActive("owner","conversation",key)).code());
        assertTrue(store.claim("owner","conversation",UUID.randomUUID().toString(),"继续聊天").fresh());
    }
    @Test void expiredInterruptedTurnCannotResumeAfterExplicitRecovery(){
        store.claim("owner","conversation",key,"生成音乐");
        jdbc.update("UPDATE agent_message SET role='system',kind='REQUEST',state='FAILED'");
        assertEquals("REQUEST_KEY_CONFLICT",assertThrows(DomainException.class,()->store.claim("owner","conversation",key,"生成音乐")).code());
        assertEquals("AGENT_TURN_RESET_CONFLICT",assertThrows(DomainException.class,()->store.complete("owner","conversation",key,"{}")).code());
    }
}
