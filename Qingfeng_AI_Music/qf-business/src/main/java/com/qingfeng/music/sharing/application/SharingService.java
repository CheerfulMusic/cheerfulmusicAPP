package com.qingfeng.music.sharing.application;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.common.exception.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class SharingService implements SharingApi {
    private final WorkApi works;private final LyricsApi lyrics;private final MediaApi media;private final ShareRepository repository;
    private final SecureRandom random=new SecureRandom();
    private final com.qingfeng.music.media.api.SharedMediaAccess audioAccess;
    public SharingService(WorkApi works,LyricsApi lyrics,MediaApi media,ShareRepository repository,com.qingfeng.music.media.api.SharedMediaAccess audioAccess){this.works=works;this.lyrics=lyrics;this.media=media;this.repository=repository;this.audioAccess=audioAccess;}
    public Created create(String owner,String work,String revisionId,String assetId){
        var w=works.get(owner,work);var revision=lyrics.get(owner,work,revisionId);var asset=media.get(owner,work,assetId);
        if(!revision.confirmed())throw new DomainException("LYRICS_NOT_CONFIRMED","请先确认歌词版本");
        if(revision.workVersion()!=w.version())throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请保存并确认当前版本的歌词");
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now=Instant.now();String id=UUID.randomUUID().toString();Instant expires=now.plus(7,ChronoUnit.DAYS);
        repository.insert(new ShareRepository.Snapshot(id,work,hash(token),assetId,revision.title(),w.recipient(),w.message(),revision.lyrics(),asset.origin(),true,now,expires));
        return new Created(id,token,expires);
    }
    public void revokeAll(String owner,String work){works.get(owner,work);repository.revokeAll(work);}
    public Gift get(String token){var share=resolve(token);return new Gift(share.title(),share.recipient(),share.message(),share.lyrics(),share.audioOrigin(),share.createdAt(),share.expiresAt());}
    public MediaApi.Content audio(String token){var share=resolve(token);return audioAccess.content(share.workId(),share.assetId());}
    private ShareRepository.Snapshot resolve(String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw notFound();
        return repository.active(hash(token),Instant.now()).orElseThrow(this::notFound);
    }
    private DomainException notFound(){return new DomainException("SHARE_NOT_FOUND","这份礼物不存在、已过期或已被撤销");}
    private static String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
