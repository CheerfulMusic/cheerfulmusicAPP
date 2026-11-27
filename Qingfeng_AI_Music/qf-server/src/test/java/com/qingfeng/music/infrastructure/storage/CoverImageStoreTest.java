package com.qingfeng.music.infrastructure.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.qingfeng.music.common.exception.DomainException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoverImageStoreTest {
    private static final String FILE = "01234567-89ab-4def-8123-456789abcdef.jpg";
    @TempDir Path directory;

    @Test void missingCloudObjectUsesTheSameCoverErrorAsMissingLocalFile() throws Exception {
        OSS oss = mock(OSS.class);
        when(oss.getObject("bucket", "covers/" + FILE)).thenThrow(ossError("NoSuchKey"));
        var store = new CoverImageStore(directory, oss, "bucket", "covers", true);
        assertEquals("COVER_NOT_FOUND", assertThrows(DomainException.class,
                () -> store.open("oss:covers/" + FILE)).code());
        assertEquals("COVER_NOT_FOUND", assertThrows(DomainException.class,
                () -> store.open("local:" + FILE)).code());
    }

    @Test void storageAuthorizationErrorsRemainDistinguishableFromMissingArtwork() throws Exception {
        OSS oss = mock(OSS.class);
        OSSException denied = ossError("AccessDenied");
        when(oss.getObject("bucket", "covers/" + FILE)).thenThrow(denied);
        var store = new CoverImageStore(directory, oss, "bucket", "covers", true);
        assertSame(denied, assertThrows(OSSException.class, () -> store.open("oss:covers/" + FILE)));
    }

    private OSSException ossError(String code) {
        return new OSSException("Offline cover storage fixture", code, "test", "test", null, null, null);
    }
}
