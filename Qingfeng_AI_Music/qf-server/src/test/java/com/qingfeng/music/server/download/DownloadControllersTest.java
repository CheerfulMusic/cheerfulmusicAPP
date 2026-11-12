package com.qingfeng.music.server.download;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.web.MediaCdnSigner;
import com.qingfeng.music.server.web.app.MediaController;
import com.qingfeng.music.server.web.publicapi.GiftController;
import com.qingfeng.music.sharing.api.SharingApi;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DownloadControllersTest {
    @Test void foreignOrMissingAssetNeverInvokesCharge() {
        var media=mock(MediaApi.class);var grants=mock(DownloadEntitlements.class);var owner=new CreatorPrincipal("user");
        when(grants.verifiedOwner(owner)).thenReturn("user");when(media.open("user","foreign","asset")).thenThrow(new DomainException("MEDIA_NOT_FOUND","missing"));
        var controller=new MediaController(media,mock(MediaCdnSigner.class),grants);
        assertThrows(DomainException.class,()->controller.unlockDownload(owner,"foreign","asset"));
        verify(grants,never()).unlock(anyString(),anyString(),any());
    }
    @Test void expiredGiftAndUnauthenticatedDownloadNeverInvokeCharge() {
        var sharing=mock(SharingApi.class);var grants=mock(DownloadEntitlements.class);var owner=new CreatorPrincipal("user");
        when(grants.verifiedOwner(owner)).thenReturn("user");when(grants.verifiedOwner(null)).thenThrow(new DomainException("DOWNLOAD_LOGIN_REQUIRED","login"));
        var shared=mock(SharedDownloadGrants.class);doThrow(new DomainException("SHARE_NOT_FOUND","expired")).when(shared).unlock("user","expired");
        var controller=new GiftController(sharing,mock(MediaCdnSigner.class),grants,shared);
        assertThrows(DomainException.class,()->controller.unlockDownload(owner,"expired"));
        assertThrows(DomainException.class,()->controller.audio("active",true,null,null));
        verify(grants,never()).unlock(anyString(),anyString(),any());verify(sharing,never()).audio("expired");
    }
}
