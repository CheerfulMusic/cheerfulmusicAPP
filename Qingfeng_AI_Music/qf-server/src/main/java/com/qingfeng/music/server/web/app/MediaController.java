package com.qingfeng.music.server.web.app;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.web.AudioResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/app/works/{work}/audio")
public class MediaController {
 private final MediaApi media;
 public MediaController(MediaApi media){this.media=media;}
 @GetMapping public List<MediaApi.Asset> list(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work){return media.list(creator.id(),work);}
 @GetMapping("/selection") public MediaApi.Selection selection(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work){return media.selection(creator.id(),work);}
 public record SelectionRequest(@jakarta.validation.constraints.NotBlank String assetId,@jakarta.validation.constraints.PositiveOrZero long version){}
 @PutMapping("/selection") public MediaApi.Selection select(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@jakarta.validation.Valid @RequestBody SelectionRequest request){return media.select(creator.id(),work,request.assetId(),request.version());}
 @PostMapping(consumes="multipart/form-data") public MediaApi.Asset upload(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@RequestParam("file") MultipartFile file)throws IOException{try(var input=file.getInputStream()){return media.importAudio(creator.id(),work,input);}}
 @GetMapping("/{asset}") public ResponseEntity<Resource> audio(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String asset,@RequestParam(defaultValue="false") boolean download){return AudioResponse.from(media.open(creator.id(),work,asset),download);}
}
