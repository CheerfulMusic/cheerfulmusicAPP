package com.qingfeng.music.server.web.publicapi;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.server.web.AudioResponse;
import com.qingfeng.music.server.web.MediaCdnSigner;
import org.springframework.core.io.Resource;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/public/gifts/{token}")
public class GiftController {
 private final SharingApi sharing;
 private final MediaCdnSigner signer;
 private final com.qingfeng.music.server.download.DownloadEntitlements downloads;
 private final com.qingfeng.music.server.download.SharedDownloadGrants grants;
 @org.springframework.beans.factory.annotation.Autowired
 public GiftController(SharingApi sharing,MediaCdnSigner signer,com.qingfeng.music.server.download.DownloadEntitlements downloads,com.qingfeng.music.server.download.SharedDownloadGrants grants){this.sharing=sharing;this.signer=signer;this.downloads=downloads;this.grants=grants;}
 public GiftController(SharingApi sharing,MediaCdnSigner signer){this(sharing,signer,null,null);}
 public record DownloadStatus(boolean unlocked,long price){}
 @GetMapping("/download") public DownloadStatus downloadStatus(@AuthenticationPrincipal com.qingfeng.music.server.security.CreatorPrincipal principal,@PathVariable String token){String owner=downloads.verifiedOwner(principal);boolean unlocked=grants.assetUnlocked(owner,token);return new DownloadStatus(unlocked,unlocked?0:grants.price(owner,token));}
 @PostMapping("/download") public DownloadStatus unlockDownload(@AuthenticationPrincipal com.qingfeng.music.server.security.CreatorPrincipal principal,@PathVariable String token){String owner=downloads.verifiedOwner(principal);grants.unlock(owner,token);return new DownloadStatus(true,0);}
 @GetMapping public ResponseEntity<SharingApi.Gift> gift(@PathVariable String token){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sharing.get(token));}
 @GetMapping("/audio") public ResponseEntity<Resource> audio(@PathVariable String token,@RequestParam(defaultValue="false") boolean download,@RequestHeader(value="Range",required=false) String range,@AuthenticationPrincipal com.qingfeng.music.server.security.CreatorPrincipal principal){
  if(download){String owner=downloads.verifiedOwner(principal);return AudioResponse.from(grants.contentForToken(owner,token),true,range);}
  if(!download&&signer.enabled())try{var location=signer.audio(sharing.audioRef(token).storageKey());if(location.isPresent())return AudioResponse.redirect(location.get());}catch(RuntimeException ignored){/* fail-closed：回退原中转，由 audio() 重新执行 resolve(token) */}
  return AudioResponse.from(sharing.audio(token),download,range);
 }
 public ResponseEntity<Resource> audio(String token,boolean download){return audio(token,download,null,null);}
 public ResponseEntity<Resource> audio(String token,boolean download,String range){return audio(token,download,range,null);}
 @GetMapping("/cover") public ResponseEntity<Resource> cover(@PathVariable String token,@RequestParam(defaultValue="false") boolean proxy){
  // 海报由浏览器在同源画布中绘制，代理读取避免 CDN 跨域污染画布。
  if(!proxy&&signer.enabled())try{var location=signer.cover(sharing.coverRef(token).storageKey());if(location.isPresent())return AudioResponse.redirect(location.get());}catch(RuntimeException ignored){/* fail-closed：回退原中转 */}
  return AudioResponse.from(sharing.cover(token),false);
 }
 public ResponseEntity<Resource> cover(String token){return cover(token,false);}
}
