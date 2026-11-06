package com.qingfeng.music.server.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * 阿里云 CDN「URL 鉴权方式 A」签发，以及音频/封面两类 storageKey 的分流解析。
 *
 * <p>本类只做「已通过授权链后」的对象定位与签名，不参与任何权限判断。调用方必须先在
 * MediaApi/SharingApi 完成归属、撤销、过期校验；任何异常或不匹配都返回 empty，由
 * Controller fail-closed 回退原中转，绝不直接签发。
 *
 * <p>timestamp 采用「签算时刻」，有效期由 CDN 控制台 TTL 决定，本类不自行延长
 * bearer 窗口。中文文档「签算时刻 + 控制台 TTL」与英文示例「exp = now + TTL」
 * 的语义差异仍属方案 E-3 待真实环境验证项，不得据此宣称已验证。
 */
public final class MediaCdnSigner {
    private static final String AUDIO_OBJECT = "[0-9a-fA-F-]{36}\\.(mp3|wav)";
    private static final String COVER_OBJECT = "[0-9a-fA-F-]{36}\\.jpg";

    private final boolean enabled;
    private final String base;
    private final String primaryKey;
    private final String backupKey;
    private final long ttlSeconds;
    private final String uid;
    private final String audioPrefix;
    private final String coverPrefix;

    public MediaCdnSigner(boolean enabled,String domain,String primaryKey,String backupKey,
                          long ttlSeconds,String uid,String audioPrefix,String coverPrefix){
        String host = domain == null ? "" : domain.trim();
        String primary = primaryKey == null ? "" : primaryKey.trim();
        this.enabled = enabled && host.startsWith("https://") && !primary.isEmpty();
        this.base = stripTrailingSlash(host);
        this.primaryKey = primary;
        this.backupKey = backupKey == null ? "" : backupKey.trim();
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : 600;
        this.uid = uid == null || uid.isBlank() ? "0" : uid.trim();
        this.audioPrefix = normalizePrefix(audioPrefix);
        this.coverPrefix = normalizePrefix(coverPrefix);
    }

    public boolean enabled(){return enabled;}
    public long ttlSeconds(){return ttlSeconds;}
    public String base(){return base;}
    public String primaryKey(){return primaryKey;}
    public String backupKey(){return backupKey;}
    public String uid(){return uid;}
    public String audioPrefix(){return audioPrefix;}
    public String coverPrefix(){return coverPrefix;}

    /** 音频 storageKey 为 {@code oss:{uuid}.mp3}，对象名不含 prefix，需显式拼 audioPrefix。 */
    public Optional<URI> audio(String storageKey){
        if(!enabled)return Optional.empty();
        String value=objectKey(storageKey);
        if(value==null||!value.matches(AUDIO_OBJECT))return Optional.empty();
        return sign("/"+audioPrefix+value,primaryKey);
    }

    /** 封面 storageKey 为 {@code oss:qingfeng/covers/{uuid}.jpg}，已含 prefix，禁止再拼。 */
    public Optional<URI> cover(String storageKey){
        if(!enabled)return Optional.empty();
        String value=objectKey(storageKey);
        if(value==null||!value.startsWith(coverPrefix))return Optional.empty();
        if(!value.substring(coverPrefix.length()).matches(COVER_OBJECT))return Optional.empty();
        return sign("/"+value,primaryKey);
    }

    /** 主/备 KEY 轮换：用备 KEY 重新签发同一 URI，供轮换期与单测使用。 */
    public Optional<URI> signWithBackup(String uri){
        if(backupKey.isEmpty()||uri==null||!uri.startsWith("/"))return Optional.empty();
        return sign(uri,backupKey);
    }

    /** auth_key = timestamp-rand-uid-md5hash；URI 以 / 开头且不含 query。 */
    Optional<URI> sign(String uri,String key){
        if(key==null||key.isEmpty()||uri==null||!uri.startsWith("/")
            ||uri.contains("?")||uri.contains("#")||uri.contains("//")||uri.contains(".."))return Optional.empty();
        long timestamp=Instant.now().getEpochSecond();
        String rand=UUID.randomUUID().toString().replace("-","");
        String authKey=timestamp+"-"+rand+"-"+uid+"-"+hash(uri,timestamp,rand,uid,key);
        try{return Optional.of(URI.create(base+uri+"?auth_key="+authKey));}
        catch(IllegalArgumentException e){return Optional.empty();}
    }

    static String hash(String uri,long timestamp,String rand,String uid,String key){
        String raw=uri+"-"+timestamp+"-"+rand+"-"+uid+"-"+key;
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(raw.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    /** 仅接受 oss: 前缀；local:、空值、路径穿越一律拒绝。不得由 filename 推导。 */
    private static String objectKey(String storageKey){
        if(storageKey==null||!storageKey.startsWith("oss:"))return null;
        String value=storageKey.substring(4);
        if(value.isEmpty()||value.startsWith("/")||value.contains("..")||value.contains("?")||value.contains("#"))return null;
        return value;
    }

    private static boolean isBlank(String value){return value==null||value.isBlank();}
    private static String stripTrailingSlash(String host){
        String value=host;
        while(value.endsWith("/"))value=value.substring(0,value.length()-1);
        return value;
    }
    private static String normalizePrefix(String prefix){
        if(isBlank(prefix))return "";
        String value=prefix.trim();
        while(value.startsWith("/"))value=value.substring(1);
        if(!value.isEmpty()&&!value.endsWith("/"))value=value+"/";
        return value;
    }
}
