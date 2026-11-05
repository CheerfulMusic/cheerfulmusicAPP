package com.qingfeng.music.infrastructure.persistence.usage;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.usage.UsageBudget;
import java.sql.Timestamp;
import java.time.*;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 用数据库行锁串行化准入，跨进程及重启后仍有效。锁只覆盖短事务，不覆盖模型 HTTP 调用。 */
public final class JdbcUsageBudget implements UsageBudget {
    public record Limits(int dailyPerCreator, int dailyGlobal, int concurrentPerCreator, int concurrentGlobal) {
        public Limits {
            if (dailyPerCreator < -1 || dailyGlobal < -1 || concurrentPerCreator < -1 || concurrentGlobal < -1)
                throw new IllegalArgumentException("生成额度仅支持-1、0或正整数：-1为不限制，0为禁止新增请求");
        }
    }
    public record Allowance(int dailyLimit, int dailyRemaining, int concurrentLimit, int active, Instant resetsAt, boolean dailyUnlimited, boolean concurrentUnlimited) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Map<Kind, Limits> limits;
    private final Clock clock;
    private final ZoneId zone;
    public JdbcUsageBudget(JdbcTemplate jdbc, TransactionTemplate transactions, Map<Kind, Limits> limits, Clock clock, ZoneId zone) {
        this.jdbc=jdbc;this.transactions=transactions;this.limits=Map.copyOf(limits);this.clock=clock;this.zone=zone;
    }
    private com.qingfeng.music.credits.CreditsLedger credits;
    private boolean creditsEnabled;
    private long musicCost;
    public JdbcUsageBudget withCredits(com.qingfeng.music.credits.CreditsLedger credits, boolean enabled, long musicCost) {
        if(musicCost<=0) throw new IllegalArgumentException("music cost must be positive");
        this.credits=credits;this.creditsEnabled=enabled;this.musicCost=musicCost;return this;
    }
    private String reference(String work,String key){return "music:"+work+":"+key;}
    public void reserve(String owner, String work, String key, Kind kind) {
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT kind FROM model_usage_lock WHERE kind=? FOR UPDATE", String.class, kind.name());
            if(kind==Kind.LYRICS)com.qingfeng.music.infrastructure.persistence.lyrics.JdbcCompositionRequests.expireStale(jdbc);
            if (count("SELECT COUNT(*) FROM model_usage_reservation WHERE kind=? AND work_id=? AND request_key=?", kind.name(),work,key)>0) return;
            var cap=limits.get(kind);var day=LocalDate.now(clock.withZone(zone));
            if (cap.dailyPerCreator()>=0 && count("SELECT COUNT(*) FROM model_usage_reservation WHERE kind=? AND usage_day=? AND owner_id=?",kind.name(),java.sql.Date.valueOf(day),owner)>=cap.dailyPerCreator())
                throw new DomainException("PERSONAL_DAILY_QUOTA_EXCEEDED","今日创作次数已用完，请明天再来；已保存的内容不会丢失。");
            if (cap.dailyGlobal()>=0 && count("SELECT COUNT(*) FROM model_usage_reservation WHERE kind=? AND usage_day=?",kind.name(),java.sql.Date.valueOf(day))>=cap.dailyGlobal())
                throw new DomainException("GLOBAL_DAILY_QUOTA_EXCEEDED","今日创作名额已满，请明天再来。");
            if (cap.concurrentPerCreator()>=0 && active(kind,owner)>=cap.concurrentPerCreator())
                throw new DomainException("PERSONAL_GENERATION_BUSY","你已有创作正在处理或核对中，请完成后再试。");
            if (cap.concurrentGlobal()>=0 && active(kind,null)>=cap.concurrentGlobal())
                throw new DomainException("GLOBAL_GENERATION_BUSY","当前创作人数较多，请稍后再试。");
            if(kind==Kind.MUSIC && creditsEnabled) credits.debit(owner,musicCost,reference(work,key));
            jdbc.update("INSERT INTO model_usage_reservation(kind,work_id,request_key,owner_id,usage_day,created_at) VALUES(?,?,?,?,?,?)",kind.name(),work,key,owner,java.sql.Date.valueOf(day),Timestamp.from(clock.instant()));
        });
    }
    public Allowance allowance(String owner, Kind kind) {
        var day=LocalDate.now(clock.withZone(zone));var cap=limits.get(kind);
        int used=count("SELECT COUNT(*) FROM model_usage_reservation WHERE kind=? AND usage_day=? AND owner_id=?",kind.name(),java.sql.Date.valueOf(day),owner);
        return new Allowance(cap.dailyPerCreator(),cap.dailyPerCreator()<0?-1:Math.max(0,cap.dailyPerCreator()-used),cap.concurrentPerCreator(),active(kind,owner),day.plusDays(1).atStartOfDay(zone).toInstant(),cap.dailyPerCreator()<0,cap.concurrentPerCreator()<0);
    }
    public boolean cancelMusic(String work,String key,java.util.function.BooleanSupplier cancel){
        return Boolean.TRUE.equals(transactions.execute(tx->{
            jdbc.queryForObject("SELECT kind FROM model_usage_lock WHERE kind='MUSIC' FOR UPDATE",String.class);
            var owners=jdbc.query("SELECT owner_id FROM model_usage_reservation WHERE kind='MUSIC' AND work_id=? AND request_key=?",(rs,n)->rs.getString(1),work,key);
            if(!cancel.getAsBoolean())return false;
            if(credits!=null && !owners.isEmpty()) credits.refund(owners.get(0),reference(work,key));
            jdbc.update("DELETE FROM model_usage_reservation WHERE kind='MUSIC' AND work_id=? AND request_key=?",work,key);
            return true;
        }));
    }
    private int active(Kind kind, String owner) {
        String table=switch(kind){case MUSIC->"generation_task";case LYRICS->"lyrics_composition_request";case TIMING->"media_lyrics_timeline";case COVER->"cover_edit_task";};
        String states=switch(kind){case MUSIC,COVER->"'QUEUED','SUBMITTING','RUNNING','SAVING','SUBMISSION_UNKNOWN'";case LYRICS->"'PENDING'";case TIMING->"'FETCHING','UNKNOWN'";};
        String column=kind==Kind.TIMING?"state":"status";
        String sql="SELECT COUNT(*) FROM "+table+" r JOIN music_work w ON w.id=r.work_id WHERE r."+column+" IN ("+states+")";
        if(kind==Kind.LYRICS)sql+=" AND r.created_at>=TIMESTAMPADD(SECOND,-180,CURRENT_TIMESTAMP)";
        return owner==null?count(sql):count(sql+" AND w.owner_id=?",owner);
    }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql,Integer.class,args); }
}
