package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@RestController
@RequestMapping("/api/public/system")
public class SystemInfoController {
    private final MusicProvider musicProvider;
    private final Environment environment;
    private final LyricsWriter lyricsWriter;
    public SystemInfoController(MusicProvider musicProvider, Environment environment,LyricsWriter lyricsWriter) { this.musicProvider = musicProvider; this.environment=environment;this.lyricsWriter=lyricsWriter; }

    @GetMapping("/info")
    public SystemInfo info() {
        boolean persisted=environment.acceptsProfiles(Profiles.of("mysql","test"));
        return new SystemInfo("青风音乐", "0.1.0-SNAPSHOT", persisted?"LOCAL_WORKFLOW_READY":"SCAFFOLD", new Capabilities(persisted&&lyricsWriter.isConfigured()&&environment.getProperty("qingfeng.lyrics.enabled",Boolean.class,false), persisted&&musicProvider.isConfigured()&&environment.getProperty("qingfeng.generation.enabled",Boolean.class,false), persisted,persisted));
    }

    public record SystemInfo(String name, String version, String stage, Capabilities capabilities) {}
    public record Capabilities(boolean lyricsGeneration, boolean musicGeneration, boolean giftSharing,boolean workPersistence) {}
}
