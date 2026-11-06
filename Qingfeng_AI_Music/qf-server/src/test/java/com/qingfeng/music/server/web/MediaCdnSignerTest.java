package com.qingfeng.music.server.web;

import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 纯单元测试：不启动 Spring、不连数据库，只校验鉴权 A 公式与分流解析。 */
class MediaCdnSignerTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String AUDIO_PATH = "/qingfeng/audio/" + ID + ".mp3";
    private static final String COVER_PATH = "/qingfeng/covers/" + ID + ".jpg";

    private MediaCdnSigner signer(){
        return new MediaCdnSigner(true,"https://cdn.example.com","primary-key","backup-key",600,"0","qingfeng/audio/","qingfeng/covers/");
    }
    private static String authKey(URI location){
        String query=location.getRawQuery();
        assertTrue(query!=null&&query.startsWith("auth_key="));
        return query.substring("auth_key=".length());
    }

    @Test void disabledOrIncompleteConfigFailsClosed(){
        assertTrue(new MediaCdnSigner(false,"https://cdn.example.com","primary-key","",600,"0","qingfeng/audio/","qingfeng/covers/").audio("oss:"+ID+".mp3").isEmpty());
        assertTrue(new MediaCdnSigner(true,"","primary-key","",600,"0","qingfeng/audio/","qingfeng/covers/").audio("oss:"+ID+".mp3").isEmpty());
        assertTrue(new MediaCdnSigner(true,"http://cdn.example.com","primary-key","",600,"0","qingfeng/audio/","qingfeng/covers/").audio("oss:"+ID+".mp3").isEmpty());
        assertTrue(new MediaCdnSigner(true,"https://cdn.example.com","","",600,"0","qingfeng/audio/","qingfeng/covers/").audio("oss:"+ID+".mp3").isEmpty());
    }

    @Test void audioKeyGetsAudioPrefix(){
        URI location=signer().audio("oss:"+ID+".mp3").orElseThrow();
        assertEquals("https",location.getScheme());
        assertEquals("cdn.example.com",location.getHost());
        assertEquals(AUDIO_PATH,location.getPath());
        assertNull(location.getRawFragment());
    }

    @Test void coverKeyAlreadyContainsPrefixAndIsNotConcatenatedAgain(){
        URI location=signer().cover("oss:qingfeng/covers/"+ID+".jpg").orElseThrow();
        assertEquals(COVER_PATH,location.getPath());
        assertFalse(location.getPath().contains("covers/qingfeng/covers"));
        // 音频 key 不能走封面解析，封面 key 也不能走音频解析
        assertTrue(signer().cover("oss:"+ID+".jpg").isEmpty());
        assertTrue(signer().audio("oss:qingfeng/covers/"+ID+".jpg").isEmpty());
    }

    @Test void authKeyMatchesTypeAFormula(){
        URI location=signer().audio("oss:"+ID+".mp3").orElseThrow();
        String[] parts=authKey(location).split("-");
        assertEquals(4,parts.length);
        assertEquals(10,parts[0].length(),"timestamp 必须是 10 位 Unix 秒");
        long timestamp=Long.parseLong(parts[0]);
        assertTrue(timestamp>0);
        assertEquals(32,parts[1].length());
        assertFalse(parts[1].contains("-"),"rand 不能含分隔符");
        assertEquals("0",parts[2]);
        assertEquals(32,parts[3].length());
        assertEquals(MediaCdnSigner.hash(AUDIO_PATH,timestamp,parts[1],"0","primary-key"),parts[3]);
    }

    @Test void backupKeySignsTheSameUriWithItsOwnHash(){
        MediaCdnSigner signer=signer();
        URI location=signer.signWithBackup(AUDIO_PATH).orElseThrow();
        String[] parts=authKey(location).split("-");
        assertEquals(MediaCdnSigner.hash(AUDIO_PATH,Long.parseLong(parts[0]),parts[1],"0","backup-key"),parts[3]);
        assertNotEquals(MediaCdnSigner.hash(AUDIO_PATH,Long.parseLong(parts[0]),parts[1],"0","primary-key"),parts[3]);
        assertTrue(signer.signWithBackup("relative/path").isEmpty());
        assertTrue(new MediaCdnSigner(true,"https://cdn.example.com","primary-key","",600,"0","qingfeng/audio/","qingfeng/covers/").signWithBackup(AUDIO_PATH).isEmpty());
    }

    @Test void localMalformedAndTraversalKeysAreRejected(){
        MediaCdnSigner signer=signer();
        assertTrue(signer.audio("local:"+ID+".mp3").isEmpty());
        assertTrue(signer.audio("oss:../secret.mp3").isEmpty());
        assertTrue(signer.audio("oss:"+ID+".exe").isEmpty());
        assertTrue(signer.audio("oss:/"+ID+".mp3").isEmpty());
        assertTrue(signer.audio(null).isEmpty());
        assertTrue(signer.cover("oss:qingfeng/covers/not-a-uuid.jpg").isEmpty());
        assertTrue(signer.cover("oss:qingfeng/audio/"+ID+".jpg").isEmpty());
        assertTrue(signer.cover("local:qingfeng/covers/"+ID+".jpg").isEmpty());
    }

    @Test void signedUriMustStartWithSlashAndContainNoQuery(){
        MediaCdnSigner signer=signer();
        assertTrue(signer.sign("relative/path","primary-key").isEmpty());
        assertTrue(signer.sign("/a?x=1","primary-key").isEmpty());
        assertTrue(signer.sign("/a#frag","primary-key").isEmpty());
        assertTrue(signer.sign("/a/../b","primary-key").isEmpty());
        assertTrue(signer.sign("/ok.mp3","").isEmpty());
        assertTrue(signer.sign("/ok.mp3","primary-key").isPresent());
    }

    @Test void ttlIsConfiguredButNotEmbeddedInSignature(){
        MediaCdnSigner signer=signer();
        assertEquals(600,signer.ttlSeconds());
        assertEquals("qingfeng/audio/",signer.audioPrefix());
        assertEquals("qingfeng/covers/",signer.coverPrefix());
        assertEquals("https://cdn.example.com",signer.base());
        // timestamp 为签算时刻；有效期由控制台 TTL 决定，本类不延长 bearer 窗口（E-3 待实测）。
        long timestamp=Long.parseLong(authKey(signer.audio("oss:"+ID+".mp3").orElseThrow()).split("-")[0]);
        long now=java.time.Instant.now().getEpochSecond();
        assertTrue(Math.abs(now-timestamp)<=2);
    }
}
