package com.qingfeng.music.server.web.app;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/app/works/{work}/shares")
public class ShareController {
 private final SharingApi sharing;
 public ShareController(SharingApi sharing){this.sharing=sharing;}
 public record Request(@Pattern(regexp="\\S+") String revisionId,@NotBlank String assetId,@Size(max=100) String workTitle, Boolean publicSong){}
 public record Result(String id,String token,java.time.Instant expiresAt,String songId){}
 @org.springframework.transaction.annotation.Transactional
 @PostMapping public Result create(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody Request request){
     var share = request.workTitle()==null ? sharing.create(creator.id(),work,request.revisionId(),request.assetId())
             : sharing.createWithWorkTitle(creator.id(),work,request.revisionId(),request.assetId(),request.workTitle());
     String songId = Boolean.TRUE.equals(request.publicSong()) ? sharing.enablePublicSong(share,request.assetId()) : null;
     return new Result(share.id(),share.token(),share.expiresAt(),songId);
 }
 @DeleteMapping public void revoke(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work){sharing.revokeAll(creator.id(),work);}
}
