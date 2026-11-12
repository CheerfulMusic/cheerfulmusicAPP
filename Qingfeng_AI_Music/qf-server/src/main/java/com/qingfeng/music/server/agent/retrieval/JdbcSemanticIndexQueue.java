package com.qingfeng.music.server.agent.retrieval;

import com.qingfeng.music.agent.retrieval.SemanticIndexQueue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Coalescing durable jobs. Claims are short DB transactions; HTTP never holds a row lock. */
public final class JdbcSemanticIndexQueue implements SemanticIndexQueue {
    public record Job(String owner,String work,long version,String token,int attempts) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final boolean enabled;
    private final com.qingfeng.music.events.DurableEvents events;
    public JdbcSemanticIndexQueue(JdbcTemplate jdbc,TransactionTemplate tx,boolean enabled){this(jdbc,tx,enabled,(topic,key,payload)->{});}
    public JdbcSemanticIndexQueue(JdbcTemplate jdbc,TransactionTemplate tx,boolean enabled,com.qingfeng.music.events.DurableEvents events){this.jdbc=jdbc;this.tx=tx;this.enabled=enabled;this.events=events;}
    @Override public void changed(String owner,String work) {
        if(!enabled)return;
        tx.executeWithoutResult(status->{jdbc.update("""
            INSERT INTO agent_semantic_index_job(work_id,owner_id,requested_version,completed_version,attempts,available_at,updated_at)
            VALUES(?,?,1,0,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE owner_id=VALUES(owner_id),requested_version=requested_version+1,attempts=0,available_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6)
            """,work,owner);
            Long version=jdbc.queryForObject("SELECT requested_version FROM agent_semantic_index_job WHERE work_id=?",Long.class,work);
            events.publish("semantic.index",work+":"+version,work);
        });
    }
    public Optional<Job> claim() {
        if(!enabled)return Optional.empty();
        return tx.execute(status->{
            // Enqueue, eligibility, lease and retry all use the database clock at the same precision.
            // Mixing CURRENT_TIMESTAMP with JVM Instant can make a just-enqueued row appear in the future.
            var rows=jdbc.queryForList("""
                SELECT work_id,owner_id,requested_version,attempts FROM agent_semantic_index_job
                WHERE requested_version>completed_version AND available_at<=CURRENT_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6))
                ORDER BY available_at,work_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """);
            if(rows.isEmpty())return Optional.empty();var row=rows.get(0);String token=UUID.randomUUID().toString();
            jdbc.update("UPDATE agent_semantic_index_job SET lease_token=?,lease_until=TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(6)) WHERE work_id=?",token,row.get("work_id"));
            return Optional.of(new Job(String.valueOf(row.get("owner_id")),String.valueOf(row.get("work_id")),((Number)row.get("requested_version")).longValue(),token,((Number)row.get("attempts")).intValue()));
        });
    }
    public boolean complete(Job job) {
        return tx.execute(status->{
            int changed=jdbc.update("UPDATE agent_semantic_index_job SET completed_version=?,lease_token=NULL,lease_until=NULL,last_error=NULL WHERE work_id=? AND lease_token=?",job.version(),job.work(),job.token());
            if(changed==1)return true;
            // The stale HTTP writer may have overwritten a newer vector after its lease was reclaimed.
            // Keep the CURRENT job owner and force another authoritative source read, including deletion.
            repairLostClaim(job);
            return false;
        });
    }
    private void repairLostClaim(Job job) {
        var rows=jdbc.queryForList("SELECT owner_id,requested_version FROM agent_semantic_index_job WHERE work_id=? FOR UPDATE",job.work());
        if(rows.isEmpty())return;
        long next=((Number)rows.get(0).get("requested_version")).longValue()+1;
        jdbc.update("UPDATE agent_semantic_index_job SET requested_version=?,available_at=CURRENT_TIMESTAMP(6),last_error='STALE_WRITE_REPAIR',updated_at=CURRENT_TIMESTAMP(6) WHERE work_id=?",next,job.work());
        events.publish("semantic.index",job.work()+":"+next,job.work());
    }
    public void retry(Job job) {
        long delay=Math.min(300,5L<<Math.min(6,job.attempts()));
        tx.executeWithoutResult(status->{
            int changed=jdbc.update("UPDATE agent_semantic_index_job SET attempts=attempts+1,available_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),lease_token=NULL,lease_until=NULL,last_error='INDEX_RETRY' WHERE work_id=? AND lease_token=?",delay,job.work(),job.token());
            // An unsuccessful HTTP response is still an unknown outcome; a lost writer needs repair too.
            if(changed==0)repairLostClaim(job);
        });
    }
    public int pending(String owner){if(!enabled)return 0;return jdbc.queryForObject("SELECT COUNT(*) FROM agent_semantic_index_job WHERE owner_id=? AND requested_version>completed_version",Integer.class,owner);}
}
