package com.qingfeng.music.lyrics.playback;

import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.common.exception.DomainException;
import java.time.*;
import java.util.List;

public final class PlaybackLyricsService {
    private final MediaApi media;
    private final PlaybackLyricsRepository repository;
    private final LyricsTimingProvider provider;
    private final boolean enabled;
    public PlaybackLyricsService(MediaApi media,PlaybackLyricsRepository repository,LyricsTimingProvider provider,boolean enabled){this.media=media;this.repository=repository;this.provider=provider;this.enabled=enabled;}
    public PlaybackLyrics.Snapshot get(String owner,String work,String asset){
        media.get(owner,work,asset);
        var source=repository.source(work,asset).orElseThrow(()->new DomainException("MEDIA_NOT_FOUND","音频不存在"));
        repository.expire(asset,Instant.now().minusSeconds(180));
        var stored=repository.find(asset);
        String state=stored.map(PlaybackLyricsRepository.Stored::status).orElse("NOT_REQUESTED");
        var lines=stored.filter(s->s.status().equals("READY")).map(s->PlaybackLyrics.lines(s.words())).orElse(List.of());
        boolean available=enabled&&provider.isConfigured()&&"kie-suno".equals(source.provider())&&source.providerTaskId()!=null&&source.audioUrl()!=null;
        return new PlaybackLyrics.Snapshot(state,source.lyrics()==null?"":source.lyrics(),lines,available);
    }
    public PlaybackLyrics.Snapshot sync(String owner,String work,String asset,String key){
        var snapshot=get(owner,work,asset);
        if(key==null||!key.matches("[0-9a-fA-F-]{36}"))throw new DomainException("INVALID_REQUEST_KEY","请提供有效的请求标识");
        if(snapshot.status().equals("READY"))return snapshot;
        if(!snapshot.canSync())throw new DomainException("LYRICS_TIMING_UNAVAILABLE","这首音频暂不支持获取同步歌词");
        var source=repository.source(work,asset).orElseThrow();
        if(!repository.claim(owner,source,key))return get(owner,work,asset);
        try{
            var words=provider.fetch(source.providerTaskId(),source.audioUrl());
            String state=PlaybackLyrics.lines(words).isEmpty()?"EMPTY":"READY";
            repository.finish(asset,key,state,words);
        }catch(LyricsTimingProvider.Rejected known){repository.finish(asset,key,"FAILED",List.of());}
        catch(RuntimeException unknown){repository.finish(asset,key,"UNKNOWN",List.of());}
        return get(owner,work,asset);
    }
}
