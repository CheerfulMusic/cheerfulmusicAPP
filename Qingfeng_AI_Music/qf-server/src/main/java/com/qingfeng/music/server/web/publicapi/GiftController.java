package com.qingfeng.music.server.web.publicapi;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.server.web.AudioResponse;
import com.qingfeng.music.server.web.MediaCdnSigner;
import org.springframework.core.io.Resource;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/public/gifts/{token}")
public class GiftController {
 private final SharingApi sharing;
 private final MediaCdnSigner signer;
 public GiftController(SharingApi sharing,MediaCdnSigner signer){this.sharing=sharing;this.signer=signer;}
 @GetMapping public ResponseEntity<SharingApi.Gift> gift(@PathVariable String token){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sharing.get(token));}
 @GetMapping("/audio") public ResponseEntity<Resource> audio(@PathVariable String token,@RequestParam(defaultValue="false") boolean download,@RequestHeader(value="Range",required=false) String range){
  if(!download&&signer.enabled())try{var location=signer.audio(sharing.audioRef(token).storageKey());if(location.isPresent())return AudioResponse.redirect(location.get());}catch(RuntimeException ignored){/* fail-closed：回退原中转，由 audio() 重新执行 resolve(token) */}
  return AudioResponse.from(sharing.audio(token),download,range);
 }
 public ResponseEntity<Resource> audio(String token,boolean download){return audio(token,download,null);}
 @GetMapping("/cover") public ResponseEntity<Resource> cover(@PathVariable String token){
  if(signer.enabled())try{var location=signer.cover(sharing.coverRef(token).storageKey());if(location.isPresent())return AudioResponse.redirect(location.get());}catch(RuntimeException ignored){/* fail-closed：回退原中转 */}
  return AudioResponse.from(sharing.cover(token),false);
 }
}
