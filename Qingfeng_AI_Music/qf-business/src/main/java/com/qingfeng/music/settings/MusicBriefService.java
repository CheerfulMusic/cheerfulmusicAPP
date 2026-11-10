package com.qingfeng.music.settings;

import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.DailyQuota;
import java.util.UUID;
import java.util.regex.Pattern;

/** AI仅给出待确认建议，不写入作品、不参与任务提交事务，失败不阻断基础生成。 */
public final class MusicBriefService {
    private final InspirationRewriteService service;
    private final boolean available;
    private final DailyQuota quota;
    private final long dailyLimit;
    private static final Pattern PROTECTED=Pattern.compile("(?i)(?:不要|避免|without\\s+|\\bno\\s+)[^，,。.;；\n]+|\\d+(?:\\.\\d+)?(?:\\s*bpm)?");
    // 音乐要求精简本次不纳入积分范围，使用不计费实例；成本用每日次数上限封顶。
    public MusicBriefService(InspirationRewriter writer,boolean enabled,int concurrency){this(writer,enabled,concurrency,DailyQuota.FREE,0L);}
    public MusicBriefService(InspirationRewriter writer,boolean enabled,int concurrency,DailyQuota quota,long dailyLimit){
        available=enabled&&writer.isConfigured();
        service=new InspirationRewriteService(writer,enabled,concurrency);
        this.quota=quota==null?DailyQuota.FREE:quota;this.dailyLimit=dailyLimit;
    }
    public boolean available(){return available;}
    public String suggest(String owner,String original){
        if(original==null||original.isBlank()||original.length()>300)throw new DomainException("INVALID_MUSIC_BRIEF","请填写1至300字的补充音乐要求。");
        // 每次精简都会产生真实模型请求，用每日次数上限封顶成本敞口。
        if(!quota.consume(owner,DailyQuota.MUSIC_BRIEF,dailyLimit))throw new DomainException("MUSIC_BRIEF_QUOTA_EXCEEDED","今日AI精简次数已用完，可直接使用当前要求生成歌曲。");
        String candidate=service.rewrite(owner,original,"mixed",UUID.randomUUID().toString());
        if(candidate.length()>300)throw invalid();
        var protectedParts=PROTECTED.matcher(original);
        String normalized=candidate.replaceAll("\\s+","").toLowerCase(java.util.Locale.ROOT);
        while(protectedParts.find())if(!normalized.contains(protectedParts.group().replaceAll("\\s+","").toLowerCase(java.util.Locale.ROOT)))throw invalid();
        return candidate;
    }
    private DomainException invalid(){return new DomainException("INVALID_MUSIC_BRIEF_RESPONSE","精简结果未保留全部关键限制，已保留原要求，你仍可直接生成歌曲。");}
}
