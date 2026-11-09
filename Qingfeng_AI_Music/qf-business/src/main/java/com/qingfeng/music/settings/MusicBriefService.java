package com.qingfeng.music.settings;

import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.common.exception.DomainException;
import java.util.regex.Pattern;

/** AI仅给出待确认建议，不写入作品、不参与任务提交事务，失败不阻断基础生成。 */
public final class MusicBriefService {
    private final InspirationRewriteService service;
    private final boolean available;
    private static final Pattern PROTECTED=Pattern.compile("(?i)(?:不要|避免|without\\s+|\\bno\\s+)[^，,。.;；\\n]+|\\d+(?:\\.\\d+)?(?:\\s*bpm)?");
    public MusicBriefService(InspirationRewriter writer,boolean enabled,int concurrency){available=enabled&&writer.isConfigured();service=new InspirationRewriteService(writer,enabled,concurrency);}
    public boolean available(){return available;}
    public String suggest(String owner,String original){
        if(original==null||original.isBlank()||original.length()>300)throw new DomainException("INVALID_MUSIC_BRIEF","请填写1至300字的补充音乐要求。");
        String candidate=service.rewrite(owner,original,"mixed");
        if(candidate.length()>300)throw invalid();
        var protectedParts=PROTECTED.matcher(original);
        String normalized=candidate.replaceAll("\\s+","").toLowerCase(java.util.Locale.ROOT);
        while(protectedParts.find())if(!normalized.contains(protectedParts.group().replaceAll("\\s+","").toLowerCase(java.util.Locale.ROOT)))throw invalid();
        return candidate;
    }
    private DomainException invalid(){return new DomainException("INVALID_MUSIC_BRIEF_RESPONSE","精简结果未保留全部关键限制，已保留原要求，你仍可直接生成歌曲。");}
}
