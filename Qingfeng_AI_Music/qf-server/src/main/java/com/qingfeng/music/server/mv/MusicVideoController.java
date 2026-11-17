package com.qingfeng.music.server.mv;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.web.AudioResponse;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.common.exception.DomainException;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import java.nio.file.*;
import java.io.IOException;
import java.util.Map;
@RestController @Profile({"mysql","test"}) @ConditionalOnProperty(name="qingfeng.video.enabled",havingValue="true")
@RequestMapping("/api/app/music-videos")
public class MusicVideoController {
 private final MusicVideoService service;private final MusicVideoWorker worker;private final VideoImages images;private final MusicVideoStoryboard storyboard;private final com.qingfeng.music.work.api.WorkApi works;
 public MusicVideoController(MusicVideoService service,MusicVideoWorker worker,VideoImages images,MusicVideoStoryboard storyboard,com.qingfeng.music.work.api.WorkApi works){this.service=service;this.worker=worker;this.images=images;this.storyboard=storyboard;this.works=works;}
 public record RetryShot(int shotIndex,String prompt){}
 @PostMapping("/{id}/retry-shot") public Map<String,String> retryShot(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id,@RequestBody RetryShot body){service.retryShot(creator.id(),id,body.shotIndex(),body.prompt());return Map.of("projectId",id);}
 public record Draft(String workId,String assetId,MusicVideoService.Plan plan,String requestKey,Integer quotedCredits){}
 @GetMapping("/options") public MusicVideoService.Quote options(@AuthenticationPrincipal CreatorPrincipal creator,@RequestParam String workId,@RequestParam String assetId){return service.quote(creator.id(),workId,assetId,"480P");}
 @GetMapping public java.util.List<Map<String,Object>> list(@AuthenticationPrincipal CreatorPrincipal creator,@RequestParam String workId){return service.list(creator.id(),workId);}
 @PostMapping("/prepare") public Map<String,Object> prepare(@AuthenticationPrincipal CreatorPrincipal creator,@RequestBody Draft draft){var plan=service.prepare(creator.id(),draft.workId(),draft.assetId(),draft.plan());return Map.of("plan",plan,"credits",MusicVideoService.price(plan),"generatedSeconds",MusicVideoService.generatedSeconds(plan));}
 @PostMapping("/storyboard") public MusicVideoService.Plan storyboard(@AuthenticationPrincipal CreatorPrincipal creator,@RequestBody Draft draft){return storyboard.create(creator.id(),draft.workId(),draft.assetId(),draft.requestKey(),draft.plan());}
 @PostMapping public Map<String,String> create(@AuthenticationPrincipal CreatorPrincipal creator,@RequestBody Draft draft){
  MusicVideoService.validate(draft.plan());
  if(draft.quotedCredits()==null||draft.quotedCredits()!=MusicVideoService.price(draft.plan()))throw new DomainException("VIDEO_QUOTE_CHANGED","请先核对完整分镜和总费用，再确认生成。");
  return Map.of("projectId",service.create(creator.id(),draft.workId(),draft.assetId(),draft.requestKey(),draft.plan()));
 }
 @PostMapping(value="/images",consumes="multipart/form-data") public Map<String,String> upload(@AuthenticationPrincipal CreatorPrincipal creator,@RequestParam String workId,@RequestParam("file") org.springframework.web.multipart.MultipartFile file)throws IOException{works.get(creator.id(),workId);try(var input=file.getInputStream()){return Map.of("imageId",images.save(creator.id(),workId,input));}}
 @GetMapping("/images/{imageId}") public ResponseEntity<Resource> image(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String imageId,@RequestParam String workId){works.get(creator.id(),workId);return ResponseEntity.ok().contentType(org.springframework.http.MediaType.IMAGE_JPEG).cacheControl(org.springframework.http.CacheControl.noStore()).body(new org.springframework.core.io.FileSystemResource(images.resolve(creator.id(),workId,imageId)));}
 @GetMapping("/{id}") public Map<String,Object> status(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id){return service.get(creator.id(),id);}
 @GetMapping("/{id}/content") public ResponseEntity<Resource> content(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id,@RequestParam(defaultValue="false") boolean download,@RequestHeader(value="Range",required=false)String range)throws IOException{
  if(!"READY".equals(service.get(creator.id(),id).get("state")))throw new DomainException("VIDEO_NOT_READY","MV 尚未完成。");
  Path file=worker.folder(id).resolve("result.mp4");
  return AudioResponse.from(new MediaApi.Content(Files.size(file),"video/mp4","music-video.mp4",()->Files.newInputStream(file)),download,range);
 }
}
