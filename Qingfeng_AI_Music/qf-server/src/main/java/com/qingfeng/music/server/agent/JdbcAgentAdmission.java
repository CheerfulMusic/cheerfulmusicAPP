package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.AgentTurnStore.Claim;
import java.util.function.Supplier;
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
            var versions=jdbc.queryForList("SELECT history_epoch FROM agent_conversation WHERE id=? AND creator_id=? FOR UPDATE",Long.class,conversation,owner);
            if(versions.isEmpty())throw new DomainException("AGENT_CONVERSATION_NOT_FOUND","会话不存在或不属于当前用户。");
            Instant now=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).toInstant();
            var rows=jdbc.queryForList("SELECT creator_id,fingerprint,state,lease_until FROM agent_request_admission WHERE id=?",id);
            if(!rows.isEmpty()){
                var row=rows.get(0);
                if(!owner.equals(row.get("creator_id"))||!fingerprint.equals(row.get("fingerprint")))throw new DomainException("REQUEST_KEY_CONFLICT","同一请求标识不能用于不同的消息。");
                if("DONE".equals(row.get("state")))return new Ticket(id,owner,true);
                if("CANCELLED".equals(row.get("state")))throw denied("REQUEST_KEY_CONFLICT","会话已经重置，此请求已取消。");
                if("ABANDONED".equals(row.get("state")))throw denied("AGENT_REQUEST_ABANDONED","本轮尚未执行，积分或对话次数已退回，请重新发送消息。");
                // A lost completion acknowledgement must replay the committed turn, never re-plan it.
                String turnId=UUID.nameUUIDFromBytes((conversation+":"+key).getBytes(StandardCharsets.UTF_8)).toString();
                var messages=jdbc.queryForList("SELECT role,state FROM agent_message WHERE id=? AND conversation_id=?",turnId,conversation);
                if(!messages.isEmpty() && "system".equals(messages.get(0).get("role")))
                    return new Ticket(id,owner,true);
                var states=messages.stream().map(message->message.get("state")).toList();
                if(states.contains("FAILED"))throw denied("REQUEST_KEY_CONFLICT","这条请求已经结束。请核对已有作品和任务后继续。");
                if(states.contains("DONE")){
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
            boolean continuation = AgentChatService.CONTINUE.equals(text.trim());
            if (continuation) {
                var latest = jdbc.queryForList("SELECT role,content_json FROM agent_message WHERE conversation_id=? AND kind='TEXT' ORDER BY seq DESC LIMIT 1", conversation);
                if (latest.isEmpty() || !"assistant".equals(latest.get(0).get("role")))
                    throw denied("AGENT_PLAN_STALE","续办计划已失效。");
                var plan = json.readTree(String.valueOf(latest.get(0).get("content_json")));
                if (!"waiting".equals(plan.path("kind").asText()) || !key.equals(plan.path("continuationKey").asText()))
                    throw denied("AGENT_PLAN_STALE","续办计划已失效。");
            }
            long reservation=continuation || com.qingfeng.music.agent.application.ExactLyricEdit.parse(text)!=null?0:limits.reservationTokens();
            if(reservation>0&&count("SELECT COALESCE(SUM(reserved_tokens),0) FROM agent_request_admission WHERE usage_day=?",day)>limits.globalDailyTokens()-reservation)
                throw denied("AGENT_TOKEN_BUDGET_EXCEEDED","平台今日对话容量已满，请稍后再试；本次未扣积分。");
            boolean paidTurn=false, purchasedPack=false;
            if(reservation>0&&count("SELECT COALESCE(SUM(reserved_tokens),0) FROM agent_request_admission WHERE creator_id=? AND usage_day=?",owner,day)>limits.userDailyTokens()-reservation){
                if(credits==null)throw denied("AGENT_TOKEN_BUDGET_EXCEEDED","今日免费对话额度已用完。");
                if(!paidConfirmed)throw denied("AGENT_CHAT_PAYMENT_REQUIRED","今日免费对话额度已用完，1 积分可支持 5 次对话，剩余次数跨会话保留。写词、生成音乐等工具另计费。");
                paidTurn=true;
                if(count("SELECT COUNT(*) FROM agent_chat_allowance WHERE creator_id=?",owner)==0)
                    jdbc.update("INSERT INTO agent_chat_allowance(creator_id,remaining_turns) VALUES(?,0)",owner);
                if(count("SELECT remaining_turns FROM agent_chat_allowance WHERE creator_id=?",owner)==0){
                    credits.debit(owner,1,debitReference(id));
                    jdbc.update("UPDATE agent_chat_allowance SET remaining_turns=5 WHERE creator_id=?",owner);
                    purchasedPack=true;
                }
                jdbc.update("UPDATE agent_chat_allowance SET remaining_turns=remaining_turns-1 WHERE creator_id=?",owner);
            }
            jdbc.update("INSERT INTO agent_request_admission(id,creator_id,conversation_id,request_key,fingerprint,conversation_version,state,usage_day,reserved_tokens,lease_until,created_at,updated_at) VALUES(?,?,?,?,?,?,'RESERVED',?,?,?,?,?)",id,owner,conversation,key,fingerprint,versions.get(0),day,reservation,Timestamp.from(now.plus(limits.lease())),Timestamp.from(now),Timestamp.from(now));
            if(paidTurn)jdbc.update("UPDATE agent_request_admission SET paid_turn=1,purchased_pack=? WHERE id=?",purchasedPack?1:0,id);
            return new Ticket(id,owner,false);
        });
    }
    @Override public Claim startTurn(Ticket ticket, String conversation, Supplier<Claim> claim) {
        if (ticket.replay()) return tx.execute(status -> {
            Claim existing = claim.get();
            if (existing.fresh()) throw denied("REQUEST_KEY_CONFLICT","原请求结果不可用，请刷新会话。");
            return existing;
        });
        Claim result = tx.execute(status -> {
            lockAdmission();
            var version = jdbc.queryForObject("SELECT history_epoch FROM agent_conversation WHERE id=? AND creator_id=? FOR UPDATE",
                    Long.class, conversation, ticket.owner());
            var rows = jdbc.queryForList("SELECT conversation_version FROM agent_request_admission WHERE id=? AND conversation_id=? AND state='RESERVED' FOR UPDATE",
                    Long.class, ticket.id(), conversation);
            if (rows.isEmpty()) throw denied("REQUEST_KEY_CONFLICT","本次请求已结束，请刷新会话。");
            if (!Objects.equals(rows.get(0), version)) {
                refundReservation(ticket, "CANCELLED", false);
                return null;
            }
            start(ticket);
            return claim.get();
        });
        // Throw after commit so the cancellation and refund remain durable.
        if (result == null) throw denied("REQUEST_KEY_CONFLICT","会话已经重置，此请求已取消。");
        return result;
    }
    @Override public void start(Ticket ticket){if(ticket.replay())return;if(jdbc.update("UPDATE agent_request_admission SET state='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='RESERVED' AND lease_until>CURRENT_TIMESTAMP",ticket.id())!=1)throw denied("AGENT_ADMISSION_LOST","本轮执行资格已失效，请先核对原请求状态。");}
    @Override public void heartbeat(Ticket ticket){if(ticket.replay())return;tx.executeWithoutResult(s->{Instant now=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).toInstant();if(jdbc.update("UPDATE agent_request_admission SET lease_until=?,updated_at=? WHERE id=? AND state='RUNNING' AND lease_until>?",Timestamp.from(now.plus(limits.lease())),Timestamp.from(now),ticket.id(),Timestamp.from(now))!=1)throw denied("AGENT_ADMISSION_LOST","本轮执行资格已失效，后续步骤已停止。");});}
    @Override public void finish(Ticket ticket,boolean completed){if(!ticket.replay())jdbc.update("UPDATE agent_request_admission SET state=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='RUNNING'",completed?"DONE":"UNCERTAIN",ticket.id());}
    @Override public void abandon(Ticket ticket){if(ticket.replay())return;tx.executeWithoutResult(s->{
        lockAdmission();
        refundReservation(ticket, "ABANDONED", true);
    });}

    private void lockAdmission() {
        jdbc.queryForObject("SELECT id FROM agent_admission_lock WHERE id=1 FOR UPDATE", Integer.class);
    }

    public void recoverExpired() {
        Instant now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).toInstant();
        var cutoff = Timestamp.from(now.minus(Duration.ofMinutes(5)));
        String cursor = "";
        while (true) {
            var batch = jdbc.queryForList("SELECT id,creator_id,conversation_id,request_key FROM agent_request_admission WHERE state='RESERVED' AND created_at<=? AND lease_until<=? AND id>? ORDER BY id LIMIT 100",
                    cutoff, Timestamp.from(now), cursor);
            for (var row : batch) {
                String id = row.get("id").toString();
                String conversation = row.get("conversation_id").toString();
                String message = UUID.nameUUIDFromBytes((conversation+":"+row.get("request_key")).getBytes(StandardCharsets.UTF_8)).toString();
                tx.executeWithoutResult(status -> {
                    lockAdmission();
                    if (count("SELECT COUNT(*) FROM agent_request_admission WHERE id=? AND state='RESERVED' AND created_at<=? AND lease_until<=?",id,cutoff,Timestamp.from(now)) == 1
                            && count("SELECT COUNT(*) FROM agent_message WHERE conversation_id=? AND id=?",conversation,message) == 0)
                        refundReservation(new Ticket(id,row.get("creator_id").toString(),false), "ABANDONED", false);
                });
                cursor = id;
            }
            if (batch.size() < 100) return;
        }
    }

    private void refundReservation(Ticket ticket, String terminal, boolean allowFreeRetry) {
        int changed = jdbc.update("UPDATE agent_request_admission SET state=?,reserved_tokens=0,updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='RESERVED'",terminal,ticket.id());
        if (changed == 0) return;
        if(count("SELECT paid_turn FROM agent_request_admission WHERE id=?",ticket.id())>0){
            boolean purchased=count("SELECT purchased_pack FROM agent_request_admission WHERE id=?",ticket.id())>0;
            long remaining=count("SELECT remaining_turns FROM agent_chat_allowance WHERE creator_id=?",ticket.owner());
            // Refund a wholly unused pack; otherwise restore just this unused turn.
            if(purchased&&remaining>=4){
                credits.refund(ticket.owner(),debitReference(ticket.id()));
                jdbc.update("UPDATE agent_chat_allowance SET remaining_turns=remaining_turns-4 WHERE creator_id=?",ticket.owner());
            }else jdbc.update("UPDATE agent_chat_allowance SET remaining_turns=remaining_turns+1 WHERE creator_id=?",ticket.owner());
        }else if(credits!=null&&count("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND reference_id=? AND entry_type='DEBIT'",ticket.owner(),debitReference(ticket.id()))>0){
            // Pre-upgrade single-turn reservations retain their original refund semantics.
            credits.refund(ticket.owner(),debitReference(ticket.id()));
        }else if (allowFreeRetry) jdbc.update("DELETE FROM agent_request_admission WHERE id=? AND state=?",ticket.id(),terminal);
    }
    private long count(String sql,Object...args){var value=jdbc.queryForObject(sql,Long.class,args);return value==null?0:value;}
    private static DomainException denied(String code,String message){return new DomainException(code,message);}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
