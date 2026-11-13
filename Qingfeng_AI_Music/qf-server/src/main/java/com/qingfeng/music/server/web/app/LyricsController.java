package com.qingfeng.music.server.web.app;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.lyrics.application.LyricsStructure;
import com.qingfeng.music.lyrics.application.LyricLineRewriteService;
import com.qingfeng.music.lyrics.application.LyricSelectionRewriteService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/app/works/{work}/lyrics")
public class LyricsController {
 private final LyricsApi lyrics;
 private final LyricsCompositionService composer;
 private final LyricLineRewriteService lineRewrite;
 private final LyricSelectionRewriteService selectionRewrite;
 public LyricsController(LyricsApi lyrics,LyricsCompositionService composer,LyricLineRewriteService lineRewrite,LyricSelectionRewriteService selectionRewrite){this.lyrics=lyrics;this.composer=composer;this.lineRewrite=lineRewrite;this.selectionRewrite=selectionRewrite;}
 public record LineRewriteRequest(@NotNull UUID requestId,@NotNull LyricLineRewriteService.Action action,
    @NotBlank @Size(max=160) String line,@Size(max=360) String context,@NotBlank @Pattern(regexp="zh-CN|en|mixed") String language){}
 public record LineRewriteResponse(String text){}
 @PostMapping("/rewrite-line")
 public LineRewriteResponse rewriteLine(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody LineRewriteRequest request){
     return new LineRewriteResponse(lineRewrite.preview(creator.id(),work,request.requestId(),request.action(),request.line(),request.context(),request.language()));
 }
 public record SelectionRewriteRequest(@NotNull UUID requestId, @NotNull @PositiveOrZero Long workVersion,
     @NotBlank @Size(max=12000) String lyrics, @NotEmpty @Size(max=12000) List<@NotNull @PositiveOrZero Integer> keptLines,
     @NotBlank @Pattern(regexp="zh-CN|en|mixed") String language){}
 public record SelectionRewriteResponse(String lyrics){}
 @PostMapping("/rewrite-selection")
 public SelectionRewriteResponse rewriteSelection(@AuthenticationPrincipal CreatorPrincipal creator, @PathVariable String work,
     @Valid @RequestBody SelectionRewriteRequest request){
     return new SelectionRewriteResponse(selectionRewrite.preview(creator.id(),work,request.workVersion(),request.requestId(),
         request.lyrics(),request.keptLines(),request.language()));
 }
 public record ComposeRequest(@NotNull @PositiveOrZero Long workVersion,
     @NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String requestKey,
     @NotBlank @Pattern(regexp="zh-CN|en|mixed") String language,
     @Size(max=1000) String instructions, @Pattern(regexp="[0-9a-fA-F-]{36}") String baseRevisionId,
     @Pattern(regexp="AUTO|AAB|ABB|ABC|ABCD|CUSTOM") String templateId, boolean chorusFirst,
     @Size(max=LyricsStructure.MAX_CUSTOM_SECTIONS) List<String> customSections){
     public ComposeRequest(Long workVersion,String requestKey,String language,String instructions,String baseRevisionId,
                           String templateId,boolean chorusFirst){
         this(workVersion,requestKey,language,instructions,baseRevisionId,templateId,chorusFirst,null);
     }
 }
 @PostMapping("/generate")
 public LyricsApi.Revision compose(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody ComposeRequest request){
     return composer.composeCustom(creator.id(),work,request.workVersion(),request.requestKey(),request.language(),
         request.instructions(),request.baseRevisionId(),request.templateId(),request.chorusFirst(),request.customSections());
 }
 @PostMapping(value="/generate-photo", consumes="multipart/form-data")
 public LyricsApi.Revision composePhoto(@AuthenticationPrincipal CreatorPrincipal creator, @PathVariable String work,
     @RequestParam @PositiveOrZero long workVersion, @RequestParam UUID requestKey,
     @RequestParam @Pattern(regexp="zh-CN|en|mixed") String language,
     @RequestParam(name="photo", required=false) List<MultipartFile> photo,
     @RequestParam(name="photos", required=false) List<MultipartFile> photos) {
     return composer.composePhoto(creator.id(),work,workVersion,requestKey.toString(),language,PhotoLyricsImage.jpegs(photo,photos));
 }
 @GetMapping("/requests/{key}") public LyricsCompositionService.RequestState requestState(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String key){return composer.requestState(creator.id(),work,key);}
 public record Request(@NotNull @PositiveOrZero Long workVersion,@NotBlank @Size(max=100) String title,@NotBlank @Size(max=12000) String lyrics,@Pattern(regexp="[0-9a-fA-F-]{36}") String baseRevisionId){}
 @GetMapping public List<LyricsApi.Revision> list(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work){return lyrics.list(creator.id(),work);}
 @GetMapping("/{revision}") public LyricsApi.Revision get(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String revision){return lyrics.get(creator.id(),work,revision);}
 @PostMapping public LyricsApi.Revision save(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody Request request){return lyrics.save(creator.id(),work,request.workVersion(),request.title(),request.lyrics(),request.baseRevisionId());}
 @PostMapping("/{revision}/confirm") public LyricsApi.Revision confirm(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String revision){return lyrics.confirm(creator.id(),work,revision);}
}
