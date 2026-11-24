package com.qingfeng.music.server.mv;

import com.qingfeng.music.media.api.MediaApi;
import java.nio.file.Path;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
@Profile({"mysql","test"})
class MusicVideoPreviewConfiguration {
    @Bean AudioDuration videoAudioDuration(MediaApi media, Environment env) {
        String storage = env.getProperty("qingfeng.video.storage-root");
        Path probes = storage == null || storage.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "qingfeng-mv-probes")
                : Path.of(storage).resolve(".probes");
        return new ProbedAudioDuration(media, probes, env.getProperty("qingfeng.video.ffprobe","ffprobe"));
    }
}
