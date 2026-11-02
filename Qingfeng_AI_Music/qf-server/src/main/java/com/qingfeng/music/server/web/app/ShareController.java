package com.qingfeng.music.server.web.app;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/app/works/{work}/shares")
public class ShareController {
 private final SharingApi sharing;
 public ShareController(SharingApi sharing){this.sharing=sharing;}
 public record Request(@NotBlank String revisionId,@NotBlank String assetId){}
 @PostMapping public SharingApi.Created create(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody Request request){return sharing.create(creator.id(),work,request.revisionId(),request.assetId());}
 @DeleteMapping public void revoke(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work){sharing.revokeAll(creator.id(),work);}
}
