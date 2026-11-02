package com.qingfeng.music.server;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.media.application.MediaRepository;
import com.qingfeng.music.infrastructure.persistence.media.*;
import com.qingfeng.music.common.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest @ActiveProfiles("test")
class MediaCapacityIntegrationTest {
    @Autowired IdentityApi identity;@Autowired WorkApi works;@Autowired MediaApi media;@Autowired MediaMapper mapper;
    @Test void failedUploadReleasesReservationAndQuotaDeniesBeforeWriting(){String owner=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("妈妈","生日","故事","祝福","民谣","歌"));MediaRepository repository=new MyBatisMediaRepository(mapper);
        assertThrows(DomainException.class,()->media.importAudio(owner,work.id(),new ByteArrayInputStream("invalid".getBytes())));
        long capacity=1073741824L;assertTrue(repository.reserve(work.id(),capacity,capacity));
        assertEquals("STORAGE_QUOTA_EXCEEDED",assertThrows(DomainException.class,()->media.importAudio(owner,work.id(),new ByteArrayInputStream("invalid".getBytes()))).code());repository.release(work.id(),capacity);assertTrue(repository.reserve(work.id(),capacity,capacity));repository.release(work.id(),capacity);
    }
}
