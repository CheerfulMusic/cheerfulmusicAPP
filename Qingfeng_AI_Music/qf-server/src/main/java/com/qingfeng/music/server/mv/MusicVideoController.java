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
 private final MusicVideoService service;private final MusicVideoWorker worker;
 public MusicVideoController(MusicVideoService service,MusicVideoWorker worker){this.service=service;this.worker=worker;}
 @GetMapping("/{id}") public Map<String,Object> status(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id){return service.get(creator.id(),id);}
 @GetMapping("/{id}/content") public ResponseEntity<Resource> content(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id,@RequestParam(defaultValue="false") boolean download,@RequestHeader(value="Range",required=false)String range)throws IOException{
  if(!"READY".equals(service.get(creator.id(),id).get("state")))throw new DomainException("VIDEO_NOT_READY","MV 尚未完成。");
  Path file=worker.folder(id).resolve("result.mp4");
  return AudioResponse.from(new MediaApi.Content(Files.size(file),"video/mp4","music-video.mp4",()->Files.newInputStream(file)),download,range);
 }
}
