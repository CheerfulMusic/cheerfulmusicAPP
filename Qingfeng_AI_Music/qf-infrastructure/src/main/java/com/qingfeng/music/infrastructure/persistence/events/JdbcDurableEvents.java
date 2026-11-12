package com.qingfeng.music.infrastructure.persistence.events;

import com.qingfeng.music.events.DurableEvents;
import com.qingfeng.music.common.exception.DomainException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Same-database outbox. Broker calls never run while a database transaction holds locks. */
public final class JdbcDurableEvents implements DurableEvents {
    public record Claimed(Event event, String token, int attempts) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public JdbcDurableEvents(JdbcTemplate db, TransactionTemplate tx) { this.db=db; this.tx=tx; }
    @Override public void publish(String topic,String key,String payload) {
        if(topic==null||!topic.matches("[a-z][a-z0-9.-]{0,79}")||key==null||key.isBlank()||key.length()>160||payload==null||payload.length()>4096)
            throw new IllegalArgumentException("Invalid durable event");
        tx.executeWithoutResult(status->{
            // INSERT IGNORE is atomic under concurrent duplicate publication; compare identity afterwards.
            db.update("INSERT IGNORE INTO task_event_outbox(id,topic,event_key,payload,state,attempts,next_attempt_at,created_at) VALUES(?,?,?,?,'READY',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),topic,key,payload);
            String stored=db.queryForObject("SELECT payload FROM task_event_outbox WHERE topic=? AND event_key=? FOR UPDATE",String.class,topic,key);
            if(!payload.equals(stored))throw new DomainException("EVENT_KEY_CONFLICT","任务事件标识已用于不同内容");
        });
    }
    public Optional<Claimed> claim(Duration lease) {
        return tx.execute(status->{
            Instant now=db.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).toInstant();
            var rows=db.query("SELECT id,topic,event_key,payload,attempts FROM task_event_outbox WHERE (state='READY' AND next_attempt_at<=?) OR (state='SENDING' AND lease_until<=?) ORDER BY created_at,id LIMIT 1",
                (r,n)->new Claimed(new Event(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),UUID.randomUUID().toString(),r.getInt(5)+1),Timestamp.from(now),Timestamp.from(now));
            if(rows.isEmpty())return Optional.empty();
            var claimed=rows.get(0);
            int won=db.update("UPDATE task_event_outbox SET state='SENDING',lease_token=?,lease_until=?,attempts=attempts+1 WHERE id=? AND ((state='READY' AND next_attempt_at<=?) OR (state='SENDING' AND lease_until<=?))",
                claimed.token(),Timestamp.from(now.plus(lease)),claimed.event().id(),Timestamp.from(now),Timestamp.from(now));
            return won==1?Optional.of(claimed):Optional.empty();
        });
    }
    public void delivered(Claimed claimed) {
        db.update("UPDATE task_event_outbox SET state='SENT',published_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL,last_error=NULL WHERE id=? AND state='SENDING' AND lease_token=?",claimed.event().id(),claimed.token());
    }
    public void failed(Claimed claimed) {
        int delay=Math.min(300,1<<Math.min(8,claimed.attempts()));
        db.update("UPDATE task_event_outbox SET state=?,next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),lease_token=NULL,lease_until=NULL,last_error='DELIVERY_FAILED' WHERE id=? AND state='SENDING' AND lease_token=?",
            claimed.attempts()>=20?"DEAD":"READY",delay,claimed.event().id(),claimed.token());
    }
    public Optional<Event> find(String id) {
        if(id==null||!id.matches("[0-9a-fA-F-]{36}"))return Optional.empty();
        return db.query("SELECT id,topic,event_key,payload FROM task_event_outbox WHERE id=?",(r,n)->new Event(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),id).stream().findFirst();
    }
    public long count(String state) { return db.queryForObject("SELECT COUNT(*) FROM task_event_outbox WHERE state=?",Long.class,state); }
}
