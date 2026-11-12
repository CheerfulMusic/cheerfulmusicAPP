package com.qingfeng.music.infrastructure.persistence.agent;

import com.qingfeng.music.agent.api.AgentTurnStore;
import com.qingfeng.music.common.exception.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Uses the existing V32 message table, without a new migration or database initialization. */
public final class JdbcAgentTurnStore implements AgentTurnStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();
    public JdbcAgentTurnStore(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }
    private String id(String conversation, String key) {
        return UUID.nameUUIDFromBytes((conversation + ":" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private void lock(String owner, String conversation) {
        var ids=jdbc.queryForList("SELECT id FROM agent_conversation WHERE id=? AND creator_id=? FOR UPDATE",String.class,conversation,owner);
        if(ids.isEmpty())throw new DomainException("AGENT_CONVERSATION_NOT_FOUND","会话不存在或不属于当前用户。");
    }
    @Override public Claim claim(String owner,String conversation,String key,String text) {
        return tx.execute(status -> {
            lock(owner,conversation);
            var prior=jdbc.queryForList("SELECT state,content_json FROM agent_message WHERE id=? AND conversation_id=?",id(conversation,key),conversation);
            if(!prior.isEmpty()) {
                var row=prior.get(0);var content=json.readTree(row.get("content_json").toString());
                if(!text.equals(content.path("text").asText()))throw new DomainException("REQUEST_KEY_CONFLICT","同一请求标识不能用于另一条消息。");
                if("FAILED".equals(row.get("state")))throw new DomainException("REQUEST_KEY_CONFLICT","这条请求已在恢复会话时结束，不能用旧标识再次执行。请先查看已有作品和任务。");
                if(!"DONE".equals(row.get("state")))throw new DomainException("AGENT_TURN_IN_PROGRESS","这条请求仍在处理或等待恢复。请刷新查看作品和任务，勿重复生成；如持续未恢复，确认现有结果后可重置会话再继续。");
                return new Claim(false,content.path("result").asText("{}"));
            }
            Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM agent_message WHERE conversation_id=? AND role='user' AND state='RUNNING'",Integer.class,conversation);
            if(active!=null&&active>0)throw new DomainException("AGENT_TURN_IN_PROGRESS","上一条消息仍在处理，请稍后查看结果。");
            Long seq=jdbc.queryForObject("SELECT COALESCE(MAX(seq),0)+1 FROM agent_message WHERE conversation_id=?",Long.class,conversation);
            jdbc.update("INSERT INTO agent_message(id,conversation_id,seq,role,kind,state,content_json,created_at) VALUES(?,?,?,'user','TEXT','RUNNING',?,?)",
                    id(conversation,key),conversation,seq,json.writeValueAsString(Map.of("text",text,"requestKey",key)),Timestamp.from(Instant.now()));
            return new Claim(true,null);
        });
    }
    @Override public void complete(String owner,String conversation,String key,String result) {
        tx.executeWithoutResult(status -> {
            lock(owner,conversation);
            requireLive(conversation,key);
            String body=jdbc.queryForObject("SELECT content_json FROM agent_message WHERE id=? AND conversation_id=?",String.class,id(conversation,key),conversation);
            var content=json.readTree(body);
            jdbc.update("UPDATE agent_message SET state='DONE',content_json=? WHERE id=? AND conversation_id=?",
                    json.writeValueAsString(Map.of("text",content.path("text").asText(),"requestKey",key,"result",result)),id(conversation,key),conversation);
        });
    }
    @Override public void assertActive(String owner,String conversation,String key) {
        tx.executeWithoutResult(status->{
            lock(owner,conversation);requireLive(conversation,key);
            // V32 has no lease column; created_at is the activity timestamp for RUNNING turns only.
            jdbc.update("UPDATE agent_message SET created_at=? WHERE id=? AND conversation_id=?",Timestamp.from(Instant.now()),id(conversation,key),conversation);
        });
    }
    private void requireLive(String conversation,String key) {
        Integer live=jdbc.queryForObject("SELECT COUNT(*) FROM agent_message WHERE id=? AND conversation_id=? AND role='user' AND state='RUNNING'",Integer.class,id(conversation,key),conversation);
        if(live==null||live!=1)throw new DomainException("AGENT_TURN_RESET_CONFLICT","原请求已结束或会话已恢复，请查看当前作品状态；不会重复执行该请求。");
    }
}
