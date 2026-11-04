package com.qingfeng.music.server.web.publicapi;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.server.web.AudioResponse;
import org.springframework.core.io.Resource;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/public/gifts/{token}")
public class GiftController {
 private final SharingApi sharing;
 public GiftController(SharingApi sharing){this.sharing=sharing;}
 @GetMapping public ResponseEntity<SharingApi.Gift> gift(@PathVariable String token){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sharing.get(token));}
 @GetMapping("/audio") public ResponseEntity<Resource> audio(@PathVariable String token,@RequestParam(defaultValue="false") boolean download){return AudioResponse.from(sharing.audio(token),download);}
 @GetMapping("/cover") public ResponseEntity<Resource> cover(@PathVariable String token){return AudioResponse.from(sharing.cover(token),false);}
}
