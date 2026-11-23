package com.qingfeng.music.server.web.app;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app/works/{work}/generations")
public class GenerationController {
 private final GenerationApi generation;
 private final ArtistProfileService artists;
 public GenerationController(GenerationApi generation,ArtistProfileService artists){this.generation=generation;this.artists=artists;}
 public record Request(@NotBlank String revisionId,@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}")String requestKey,Boolean publishToDiscover,@Size(max=30) String stageName,Boolean useAudioReference){}
 public record AudioReferenceAvailability(boolean available){}
 @GetMapping("/audio-reference") public AudioReferenceAvailability audioReferenceAvailable(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work){
    return new AudioReferenceAvailability(generation.audioReferenceAvailable(creator.id(),work));
 }
 public record PreviewRequest(@NotBlank String revisionId){}
 @PostMapping("/preflight") public GenerationApi.Preflight preflight(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody PreviewRequest request){return generation.preflight(creator.id(),work,request.revisionId());}
 @PostMapping public GenerationApi.Task submit(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody Request request){
  return generation.submit(creator.id(),work,request.revisionId(),request.requestKey(),Boolean.TRUE.equals(request.useAudioReference()),
    Boolean.TRUE.equals(request.publishToDiscover())?task->artists.queuePublication(creator.id(),task,request.stageName()):task->{});
 }
 @GetMapping public List<GenerationApi.Task> list(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work){return generation.list(creator.id(),work);}
 @PostMapping("/{task}/cancel") public GenerationApi.Task cancel(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String task){return generation.cancel(creator.id(),work,task);}
 public record CloseCheckingRequest(@jakarta.validation.constraints.AssertTrue boolean acknowledge){}
 @PostMapping("/{task}/close-checking") public GenerationApi.Task closeChecking(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String task,@Valid @RequestBody CloseCheckingRequest request){return generation.closeChecking(creator.id(),work,task,request.acknowledge());}
 @PostMapping("/{task}/retry-archive") public void retryArchive(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String task){generation.retryArchive(creator.id(),work,task);}
}
