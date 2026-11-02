package com.qingfeng.music.server.config;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.LyricsService;
import com.qingfeng.music.media.application.MediaService;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.sharing.application.SharingService;
import com.qingfeng.music.infrastructure.persistence.lyrics.*;
import com.qingfeng.music.infrastructure.persistence.media.*;
import com.qingfeng.music.infrastructure.persistence.sharing.*;
import com.qingfeng.music.infrastructure.storage.LocalMediaFileStore;
import java.nio.file.Path;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@Profile({"mysql","test"})
public class GiftConfiguration {
 @Bean com.qingfeng.music.generation.api.GenerationApi generationApi(WorkApi works,LyricsApi lyrics,com.qingfeng.music.generation.application.port.MusicProvider provider,com.qingfeng.music.infrastructure.persistence.generation.TaskMapper mapper,com.qingfeng.music.generation.application.ExecutionRepository execution,@Value("${qingfeng.generation.enabled:false}")boolean enabled){return new com.qingfeng.music.generation.application.GenerationService(works,lyrics,provider,new com.qingfeng.music.infrastructure.persistence.generation.MyBatisTaskRepository(mapper),execution,enabled);}
 @Bean LyricsApi lyricsApi(WorkApi works,LyricsMapper mapper){return new LyricsService(works,new MyBatisLyricsRepository(mapper));}
 @Bean MediaService mediaApi(WorkApi works,MediaMapper mapper,com.qingfeng.music.media.application.MediaFileStore files,@Value("${qingfeng.media.creator-capacity-bytes:1073741824}")long capacity){return new MediaService(works,new MyBatisMediaRepository(mapper),files,capacity);}
 @Bean SharingApi sharingApi(WorkApi works,LyricsApi lyrics,MediaService media,ShareMapper mapper){return new SharingService(works,lyrics,media,new MyBatisShareRepository(mapper),media);}
}
