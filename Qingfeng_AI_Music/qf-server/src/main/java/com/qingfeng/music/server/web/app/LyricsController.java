package com.qingfeng.music.server.web.app;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/app/works/{work}/lyrics")
public class LyricsController {
 private final LyricsApi lyrics;
 private final LyricsCompositionService composer;
 public LyricsController(LyricsApi lyrics,LyricsCompositionService composer){this.lyrics=lyrics;this.composer=composer;}
 public record ComposeRequest(@NotNull @PositiveOrZero Long workVersion,@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}")String requestKey,@NotBlank @Pattern(regexp="zh-CN|en|mixed")String language,@Size(max=1000)String instructions,@Pattern(regexp="[0-9a-fA-F-]{36}")String baseRevisionId){}
 @PostMapping("/generate") public LyricsApi.Revision compose(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody ComposeRequest request){return composer.compose(creator.id(),work,request.workVersion(),request.requestKey(),request.language(),request.instructions(),request.baseRevisionId());}
 @GetMapping("/requests/{key}") public LyricsCompositionService.RequestState requestState(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String key){return composer.requestState(creator.id(),work,key);}
 public record Request(@NotNull @PositiveOrZero Long workVersion,@NotBlank @Size(max=100) String title,@NotBlank @Size(max=12000) String lyrics){}
 @GetMapping public List<LyricsApi.Revision> list(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work){return lyrics.list(creator.id(),work);}
 @GetMapping("/{revision}") public LyricsApi.Revision get(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String revision){return lyrics.get(creator.id(),work,revision);}
 @PostMapping public LyricsApi.Revision save(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody Request request){return lyrics.save(creator.id(),work,request.workVersion(),request.title(),request.lyrics());}
 @PostMapping("/{revision}/confirm") public LyricsApi.Revision confirm(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String revision){return lyrics.confirm(creator.id(),work,revision);}
}
