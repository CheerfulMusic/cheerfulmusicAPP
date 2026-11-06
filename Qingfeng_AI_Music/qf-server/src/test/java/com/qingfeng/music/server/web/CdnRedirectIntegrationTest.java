package com.qingfeng.music.server.web;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.web.app.MediaController;
import com.qingfeng.music.server.web.publicapi.GiftController;
import com.qingfeng.music.sharing.api.SharingApi;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Web 层分支集成测试：Controller + MediaCdnSigner + AudioResponse 真实协作，
 * 仅对领域接口打桩。不启动 Spring、不连数据库、不访问生产资源。
 */
class CdnRedirectIntegrationTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final CreatorPrincipal OWNER = new CreatorPrincipal("creator-1");

    private MediaCdnSigner signer(boolean enabled){
        return new MediaCdnSigner(enabled,"https://cdn.example.com","primary-key","backup-key",600,"0","qingfeng/audio/","qingfeng/covers/");
    }
    private MediaApi.Content content(){
        return new MediaApi.Content(12,"audio/mpeg","song.mp3",()->new ByteArrayInputStream(new byte[12]));
    }
    private MediaApi.MediaRef audioRef(String key){return new MediaApi.MediaRef(key,12,"audio/mpeg","song.mp3");}
    private MediaApi.MediaRef coverRef(String key){return new MediaApi.MediaRef(key,12,"image/jpeg","cover.jpg");}

    @Test void ossAudioIssues302WithNoStoreAndNoReferrerWithoutOpeningStream(){
        MediaApi media=mock(MediaApi.class);
        when(media.locate("creator-1","w1","a1")).thenReturn(audioRef("oss:"+ID+".mp3"));
        ResponseEntity<Resource> response=new MediaController(media,signer(true)).audio(OWNER,"w1","a1",false);
        assertEquals(HttpStatus.FOUND,response.getStatusCode());
        assertEquals("no-store",response.getHeaders().getCacheControl());
        assertEquals("no-referrer",response.getHeaders().getFirst("Referrer-Policy"));
        assertTrue(response.getHeaders().getLocation().toString().contains("/qingfeng/audio/"+ID+".mp3"));
        assertTrue(response.getHeaders().getLocation().toString().contains("auth_key="));
        verify(media,never()).open(anyString(),anyString(),anyString());
    }

    @Test void downloadAndLocalKeysFallBackToOriginalStreaming(){
        MediaApi media=mock(MediaApi.class);
        when(media.open("creator-1","w1","a1")).thenReturn(content());
        MediaController controller=new MediaController(media,signer(true));
        ResponseEntity<Resource> download=controller.audio(OWNER,"w1","a1",true);
        assertEquals(HttpStatus.OK,download.getStatusCode());
        assertTrue(download.getHeaders().getContentDisposition().isAttachment());
        assertEquals("no-store",download.getHeaders().getCacheControl());
        verify(media,never()).locate(anyString(),anyString(),anyString());

        when(media.locate("creator-1","w1","a1")).thenReturn(audioRef("local:"+ID+".mp3"));
        ResponseEntity<Resource> local=controller.audio(OWNER,"w1","a1",false);
        assertEquals(HttpStatus.OK,local.getStatusCode());
        assertNull(local.getHeaders().getLocation());
        verify(media,times(2)).open("creator-1","w1","a1");
    }

    @Test void disabledCdnNeverRedirectsAndSkipsAllExtraLookups(){
        MediaApi media=mock(MediaApi.class);
        when(media.open("creator-1","w1","a1")).thenReturn(content());
        when(media.locate("creator-1","w1","a1")).thenReturn(audioRef("oss:"+ID+".mp3"));
        ResponseEntity<Resource> response=new MediaController(media,signer(false)).audio(OWNER,"w1","a1",false);
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertNull(response.getHeaders().getLocation());
        // CDN 关闭时行为必须与改动前一致，不得额外触发授权与文件存在性检查。
        verify(media,never()).locate(anyString(),anyString(),anyString());

        SharingApi sharing=mock(SharingApi.class);
        when(sharing.audio("token")).thenReturn(content());
        when(sharing.cover("token")).thenReturn(new MediaApi.Content(12,"image/jpeg","cover.jpg",()->new ByteArrayInputStream(new byte[12])));
        GiftController controller=new GiftController(sharing,signer(false));
        assertEquals(HttpStatus.OK,controller.audio("token",false).getStatusCode());
        assertEquals(HttpStatus.OK,controller.cover("token").getStatusCode());
        verify(sharing,never()).audioRef(anyString());
        verify(sharing,never()).coverRef(anyString());
    }

    @Test void authorizationFailureFailsClosedAndReRunsOpen(){
        MediaApi media=mock(MediaApi.class);
        DomainException denied=new DomainException("MEDIA_NOT_FOUND","音频不存在");
        when(media.locate("creator-1","w1","a1")).thenThrow(denied);
        when(media.open("creator-1","w1","a1")).thenThrow(denied);
        assertThrows(DomainException.class,()->new MediaController(media,signer(true)).audio(OWNER,"w1","a1",false));
        verify(media).open("creator-1","w1","a1");
    }

    @Test void giftAudioAndCoverUseSeparateKeyParsing(){
        SharingApi sharing=mock(SharingApi.class);
        when(sharing.audioRef("token")).thenReturn(audioRef("oss:"+ID+".mp3"));
        when(sharing.coverRef("token")).thenReturn(coverRef("oss:qingfeng/covers/"+ID+".jpg"));
        GiftController controller=new GiftController(sharing,signer(true));
        ResponseEntity<Resource> audio=controller.audio("token",false);
        assertEquals(HttpStatus.FOUND,audio.getStatusCode());
        assertTrue(audio.getHeaders().getLocation().getPath().startsWith("/qingfeng/audio/"));
        ResponseEntity<Resource> cover=controller.cover("token");
        assertEquals(HttpStatus.FOUND,cover.getStatusCode());
        assertEquals("/qingfeng/covers/"+ID+".jpg",cover.getHeaders().getLocation().getPath());
        assertFalse(cover.getHeaders().getLocation().getPath().contains("covers/qingfeng/covers"));
    }

    @Test void giftDownloadAndRevocationNeverIssue302(){
        SharingApi sharing=mock(SharingApi.class);
        when(sharing.audio("token")).thenReturn(content());
        GiftController controller=new GiftController(sharing,signer(true));
        assertEquals(HttpStatus.OK,controller.audio("token",true).getStatusCode());
        verify(sharing,never()).audioRef(anyString());

        DomainException revoked=new DomainException("SHARE_NOT_FOUND","礼物已撤销");
        when(sharing.audioRef("revoked")).thenThrow(revoked);
        when(sharing.audio("revoked")).thenThrow(revoked);
        assertThrows(DomainException.class,()->controller.audio("revoked",false));
        when(sharing.coverRef("revoked")).thenThrow(revoked);
        when(sharing.cover("revoked")).thenThrow(revoked);
        assertThrows(DomainException.class,()->controller.cover("revoked"));
    }

    @Test void missingCoverFallsBackAndDoesNotSign(){
        SharingApi sharing=mock(SharingApi.class);
        when(sharing.cover("token")).thenReturn(new MediaApi.Content(12,"image/jpeg","cover.jpg",()->new ByteArrayInputStream(new byte[12])));
        when(sharing.coverRef("token")).thenThrow(new DomainException("COVER_NOT_FOUND","礼物使用默认封面"));
        ResponseEntity<Resource> response=new GiftController(sharing,signer(true)).cover("token");
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertNull(response.getHeaders().getLocation());
    }
}
