package com.qingfeng.music.server.agent;

import com.qingfeng.music.common.exception.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** MySQL is authoritative in both DB and Redis modes; no network calls inside these transactions. */
public final class JdbcAgentAdmission implements AgentAdmission {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Limits limits;private final ZoneId zone;
    private com.qingfeng.music.credits.CreditsLedger credits;
    public JdbcAgentAdmission withCredits(com.qingfeng.music.credits.CreditsLedger credits){this.credits=credits;return this;}
    private static String debitReference(String id){return "agent-chat:"+id;}
    private final ObjectMapper json=new ObjectMapper();
    public JdbcAgentAdmission(JdbcTemplate jdbc,TransactionTemplate source,Limits limits,ZoneId zone){
        this.jdbc=jdbc;this.limits=limits;this.zone=zone;this.tx=new TransactionTemplate(source.getTransactionManager());
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
    }
    @Override public Ticket acquire(String owner,String conversation,String key,String text,String locale){
        return acquire(owner,conversation,key,text,locale,false);
    }
    @Override public Ticket acquire(String owner,String conversation,String key,String text,String locale,boolean paidConfirmed){
        if(text==null||text.isBlank()||text.length()>4000)throw new DomainException("INVALID_AGENT_MESSAGE","请输入 1–4000 字的消息。");
        try{UUID.fromString(key);}catch(Exception e){throw new DomainException("INVALID_REQUEST_KEY","缺少有效的消息请求标识。");}
        String payload=json.writeValueAsString(Map.of("text",text.trim(),"locale",locale==null?"zh-CN":locale));
        String id=hash(conversation+":"+key),fingerprint=hash(payload);
        return tx.execute(status->{
            jdbc.queryForObject("SELECT id FROM agent_admission_lock WHERE id=1 FOR UPDATE",Integer.class);
            if(jdbc.queryForList("SELECT id FROM agent_conversation WHERE id=? AND creator_id=?",String.class,conversation,owner).isEmpty())throw new DomainException("AGENT_CONVERSATION_NOT_FOUND","会话不存在或不属于当前用户。");
            Instant now=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).toInstant();
            var rows=jdbc.queryForList("SELECT creator_id,fingerprint,state,lease_until FROM agent_request_admission WHERE id=?",id);
            if(!rows.isEmpty()){
                var row=rows.get(0);
                if(!owner.equals(row.get("creator_id"))||!fingerprint.equals(row.get("fingerprint")))throw new DomainException("REQUEST_KEY_CONFLICT","同一请求标识不能用于不同的消息。");
                if("DONE".equals(row.get("state")))return new Ticket(id,owner,true);
                if("ABANDONED".equals(row.get("state")))throw denied("AGENT_REQUEST_ABANDONED","本轮尚未执行，积分已退回，请重新发送消息。");
                // A lost completion acknowledgement must replay the committed turn, never re-plan it.
                String turnId=UUID.nameUUIDFromBytes((conversation+":"+key).getBytes(StandardCharsets.UTF_8)).toString();
                if(count("SELECT COUNT(*) FROM agent_message WHERE id=? AND conversation_id=? AND role='user' AND state='DONE'",turnId,conversation)>0){
                    jdbc.update("UPDATE agent_request_admission SET state='DONE',updated_at=CURRENT_TIMESTAMP WHERE id=?",id);return new Ticket(id,owner,true);
                }
                throw new DomainException("AGENT_TURN_IN_PROGRESS","原请求正在处理或等待核对。请查看当前作品和任务；不会自动重新执行这条请求。");
            }
            if(count("SELECT COUNT(*) FROM agent_message WHERE conversation_id=? AND role='user' AND state='RUNNING'",conversation)>0
                ||count("SELECT COUNT(*) FROM agent_request_admission WHERE conversation_id=? AND state IN ('RESERVED','RUNNING') AND lease_until>?",conversation,Timestamp.from(now))>0)throw denied("AGENT_TURN_IN_PROGRESS","上一条消息仍在处理或等待核对，请先查看原请求结果。");
            var day=java.sql.Date.valueOf(LocalDate.ofInstant(now,zone));
            if(count("SELECT COUNT(*) FROM agent_request_admission WHERE state IN ('RESERVED','RUNNING') AND lease_until>?",Timestamp.from(now))>=limits.globalConcurrent()
                ||count("SELECT COUNT(*) FROM agent_request_admission WHERE creator_id=? AND state IN ('RESERVED','RUNNING') AND lease_until>?",owner,Timestamp.from(now))>=limits.userConcurrent())throw denied("AGENT_BUSY","当前对话请求较多，请稍后再试；本次尚未开始执行。");
            if(count("SELECT COUNT(*) FROM agent_request_admission WHERE created_at>?",Timestamp.from(now.minusSeconds(60)))>=limits.globalRpm()
                ||count("SELECT COUNT(*) FROM agent_request_admission WHERE creator_id=? AND created_at>?",owner,Timestamp.from(now.minusSeconds(60)))>=limits.userRpm())throw denied("AGENT_RATE_LIMITED","对话请求较频繁，请稍后再试；本次尚未开始执行。");
            long reservation=com.qingfeng.music.agent.application.ExactLyricEdit.parse(text)!=null?0:limits.reservationTokens();
            if(reservation>0&&count("SELECT COALESCE(SUM(reserved_tokens),0) FROM agent_request_admission WHERE usage_day=?",day)>limits.globalDailyTokens()-reservation)
                throw denied("AGENT_TOKEN_BUDGET_EXCEEDED","平台今日对话容量已满，请稍后再试；本次未扣积分。");
            if(reservation>0&&count("SELECT COALESCE(SUM(reserved_tokens),0) FROM agent_request_admission WHERE creator_id=? AND usage_day=?",owner,day)>limits.userDailyTokens()-reservation){
                if(credits==null)throw denied("AGENT_TOKEN_BUDGET_EXCEEDED","今日免费对话额度已用完。");
                if(!paidConfirmed)throw denied("AGENT_CHAT_PAYMENT_REQUIRED","今日免费对话额度已用完，继续本轮对话消耗 1 积分。写词、生成音乐等工具按各自价格另计。执行状态不明时保留扣费待核对。");
                credits.debit(owner,1,debitReference(id));
            }
            jdbc.update("INSERT INTO agent_request_admission(id,creator_id,conversation_id,request_key,fingerprint,state,usage_day,reserved_tokens,lease_until,created_at,updated_at) VALUES(?,?,?,?,?,'RESERVED',?,?,?,?,?)",id,owner,conversation,key,fingerprint,day,reservation,Timestamp.from(now.plus(limits.lease())),Timestamp.from(now),Timestamp.from(now));
            return new Ticket(id,owner,false);
        });
    }
    @Override public void start(Ticket ticket){if(ticket.replay())return;if(jdbc.update("UPDATE agent_request_admission SET state='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='RESERVED' AND lease_until>CURRENT_TIMESTAMP",ticket.id())!=1)throw denied("AGENT_ADMISSION_LOST","本轮执行资格已失效，请先核对原请求状态。");}
    @Override public void heartbeat(Ticket ticket){if(ticket.replay())return;tx.executeWithoutResult(s->{Instant now=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).toInstant();if(jdbc.update("UPDATE agent_request_admission SET lease_until=?,updated_at=? WHERE id=? AND state='RUNNING' AND lease_until>?",Timestamp.from(now.plus(limits.lease())),Timestamp.from(now),ticket.id(),Timestamp.from(now))!=1)throw denied("AGENT_ADMISSION_LOST","本轮执行资格已失效，后续步骤已停止。");});}
    @Override public void finish(Ticket ticket,boolean completed){if(!ticket.replay())jdbc.update("UPDATE agent_request_admission SET state=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND state IN ('RESERVED','RUNNING')",completed?"DONE":"UNCERTAIN",ticket.id());}
    @Override public void abandon(Ticket ticket){if(ticket.replay())return;tx.executeWithoutResult(s->{
        jdbc.queryForObject("SELECT id FROM agent_admission_lock WHERE id=1 FOR UPDATE",Integer.class);
        if(count("SELECT COUNT(*) FROM agent_request_admission WHERE id=? AND state='RESERVED'",ticket.id())==0)return;
        if(credits!=null&&count("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND reference_id=? AND entry_type='DEBIT'",ticket.owner(),debitReference(ticket.id()))>0){
            credits.refund(ticket.owner(),debitReference(ticket.id()));
            jdbc.update("UPDATE agent_request_admission SET state='ABANDONED',reserved_tokens=0,updated_at=CURRENT_TIMESTAMP WHERE id=?",ticket.id());
        }else jdbc.update("DELETE FROM agent_request_admission WHERE id=? AND state='RESERVED'",ticket.id());
    });}
    private long count(String sql,Object...args){var value=jdbc.queryForObject(sql,Long.class,args);return value==null?0:value;}
    private static DomainException denied(String code,String message){return new DomainException(code,message);}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
