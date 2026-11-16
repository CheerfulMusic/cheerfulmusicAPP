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
 public record Request(@Pattern(regexp="\\S+") String revisionId,@NotBlank String assetId,@Size(max=100) String workTitle){}
 @PostMapping public SharingApi.Created create(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody Request request){
     if(request.workTitle()==null)return sharing.create(creator.id(),work,request.revisionId(),request.assetId());
     return sharing.createWithWorkTitle(creator.id(),work,request.revisionId(),request.assetId(),request.workTitle());
 }
 @DeleteMapping public void revoke(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work){sharing.revokeAll(creator.id(),work);}
}
