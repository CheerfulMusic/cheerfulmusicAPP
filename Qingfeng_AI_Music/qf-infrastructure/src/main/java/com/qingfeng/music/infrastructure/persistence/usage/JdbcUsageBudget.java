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
    private com.qingfeng.music.credits.CreditConsumptions consumptions;
    private com.qingfeng.music.credits.CreditsPricing pricing;
    public JdbcUsageBudget withCredits(com.qingfeng.music.credits.CreditsLedger credits, com.qingfeng.music.credits.CreditConsumptions consumptions, com.qingfeng.music.credits.CreditsPricing pricing) {
        this.credits=credits;this.consumptions=consumptions;this.pricing=pricing;return this;
    }
    private String reference(Kind kind,String work,String key){return com.qingfeng.music.credits.CreditReferences.of(kind.name(),work,key);}
    /** 只有音乐和歌词是计费创作；价格来自统一定价服务，扣费与额度预留在同一事务内提交。 */
    private void charge(String owner,String work,String key,Kind kind){
        if(credits==null||pricing==null||!pricing.enabled())return;
        var priced=kind==Kind.MUSIC?com.qingfeng.music.credits.CreditsPricing.Kind.MUSIC:kind==Kind.LYRICS?com.qingfeng.music.credits.CreditsPricing.Kind.LYRICS:null;
        if(priced==null)return;
        long cost=pricing.cost(priced);
        credits.debit(owner,cost,reference(kind,work,key));
        // 消费流水目前只覆盖歌词生成；歌曲生成沿用原有记账口径，不在本次改动范围内。
        if(consumptions!=null&&kind==Kind.LYRICS)consumptions.reserved(owner,work,key,com.qingfeng.music.credits.CreditConsumptions.TYPE_LYRICS,cost);
    }
    public void reserve(String owner, String work, String key, Kind kind) {
        transactions.executeWithoutResult(tx -> {
            // Serialize with guest migration before charging or creating an owner-scoped reservation.
            if(jdbc.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner).isEmpty()
                ||jdbc.queryForList("SELECT token_hash FROM creator_session WHERE creator_id=? AND expires_at>CURRENT_TIMESTAMP FOR UPDATE",owner).isEmpty())
                throw new DomainException("CREATOR_SESSION_CHANGED","登录身份已改变，请刷新后重试；本机草稿已保留");
            var owners=jdbc.query("SELECT owner_id FROM music_work WHERE id=? AND deleted_at IS NULL",(rs,n)->rs.getString(1),work);
            if(owners.isEmpty()||!owner.equals(owners.get(0)))throw new DomainException("CREATOR_SESSION_CHANGED","作品归属已改变，请刷新后重试");
            jdbc.queryForObject("SELECT kind FROM model_usage_lock WHERE kind=? FOR UPDATE", String.class, kind.name());
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
            charge(owner,work,key,kind);
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
            if(credits!=null && !owners.isEmpty()) credits.refund(owners.get(0),reference(Kind.MUSIC,work,key));
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
