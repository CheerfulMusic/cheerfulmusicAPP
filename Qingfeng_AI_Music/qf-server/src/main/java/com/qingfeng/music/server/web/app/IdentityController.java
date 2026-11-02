package com.qingfeng.music.server.web.app;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.Map;
@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app/identity/recovery")
public class IdentityController {
    private final IdentityApi identity;
    public IdentityController(IdentityApi identity){this.identity=identity;}
    @GetMapping public ResponseEntity<Map<String,Boolean>> status(@AuthenticationPrincipal CreatorPrincipal principal){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("configured",identity.hasRecoveryCode(principal.id())));}
    @PostMapping public ResponseEntity<Map<String,String>> issue(@AuthenticationPrincipal CreatorPrincipal principal){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("code",identity.issueRecoveryCode(principal.id())));}
}
