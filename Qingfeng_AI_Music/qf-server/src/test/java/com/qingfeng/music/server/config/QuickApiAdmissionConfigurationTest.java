package com.qingfeng.music.server.config;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.creation.*;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.work.api.WorkApi;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QuickApiAdmissionConfigurationTest {
    final QuickCreationRepository repository=mock(QuickCreationRepository.class);final WorkApi works=mock(WorkApi.class);
    final LyricsCompositionService composer=mock(LyricsCompositionService.class);final GenerationApi generation=mock(GenerationApi.class);
    QuickCreationService service(MockEnvironment env,boolean providerReady){
        var writer=mock(LyricsWriter.class);when(writer.isConfigured()).thenReturn(true);var music=mock(MusicProvider.class);when(music.isConfigured()).thenReturn(providerReady);
        when(works.get("owner","work")).thenReturn(new WorkApi.Work("work","朋友","毕业","一起毕业","","民谣","毕业歌",0,Instant.now(),Instant.now()));
        when(repository.create(any())).thenAnswer(call->call.getArgument(0));when(generation.list("owner","work")).thenReturn(List.of());
        return new QuickCreationConfiguration().quickCreationService(repository,works,mock(LyricsApi.class),composer,generation,env,writer,music);
    }
    MockEnvironment environment(){return new MockEnvironment().withProperty("qingfeng.generation.enabled","true").withProperty("qingfeng.lyrics.enabled","true").withProperty("qingfeng.quick.scheduler-enabled","false");}
    @Test void apiRoleCanPersistQuickRequestWithoutStartingAnyCreationInRequestThread(){
        var env=environment().withProperty("qingfeng.quick.accept-enabled","true");var result=service(env,true).start("owner","work",0,UUID.randomUUID().toString(),"zh-CN");
        assertEquals("QUEUED",result.status());verify(repository).create(any());verifyNoInteractions(composer);verify(generation,never()).submit(anyString(),anyString(),anyString(),anyString());assertFalse(QuickCreationConfiguration.schedulerEnabled(env));
    }
    @Test void schedulerDisabledKeepsDefaultLocalAdmissionOffAndExplicitOffOverridesOptIn(){
        for(var env:List.of(environment(),environment().withProperty("qingfeng.quick.accept-enabled","false"))){assertEquals("QUICK_CREATION_UNAVAILABLE",assertThrows(DomainException.class,()->service(env,true).start("owner","work",0,UUID.randomUUID().toString(),"zh-CN")).code());}
        verify(repository,never()).create(any());
    }
    @Test void apiOptInDoesNotBypassProviderOrBusinessEnablement(){
        var env=environment().withProperty("qingfeng.quick.accept-enabled","true");assertThrows(DomainException.class,()->service(env,false).start("owner","work",0,UUID.randomUUID().toString(),"zh-CN"));
        env.withProperty("qingfeng.generation.enabled","false");assertThrows(DomainException.class,()->service(env,true).start("owner","work",0,UUID.randomUUID().toString(),"zh-CN"));verify(repository,never()).create(any());
    }
}
