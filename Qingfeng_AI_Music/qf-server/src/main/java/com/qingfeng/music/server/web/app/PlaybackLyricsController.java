package com.qingfeng.music.server.web.app;
import com.qingfeng.music.lyrics.playback.*;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/app/works/{work}/audio/{asset}/lyrics")
public class PlaybackLyricsController {
    private final PlaybackLyricsService service;
    public PlaybackLyricsController(PlaybackLyricsService service){this.service=service;}
    @GetMapping public PlaybackLyrics.Snapshot get(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String asset){return service.get(creator.id(),work,asset);}
    public record Request(@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}")String requestKey){}
    @PostMapping("/sync") public PlaybackLyrics.Snapshot sync(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String asset,@Valid @RequestBody Request request){return service.sync(creator.id(),work,asset,request.requestKey());}
}
