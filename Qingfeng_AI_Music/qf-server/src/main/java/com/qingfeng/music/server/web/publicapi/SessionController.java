package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.CreatorSessionFilter;
import jakarta.servlet.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"mysql","test"})
@RequestMapping("/api/public")
public class SessionController {
    private final IdentityApi identity;
    public SessionController(IdentityApi identity) { this.identity=identity; }
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken()); }
    @PostMapping("/session") public ResponseEntity<Map<String,String>> session(HttpServletRequest request) {
        var existing=identity.resolve(CreatorSessionFilter.token(request));
        if (existing.isPresent()) {
            var body=new java.util.LinkedHashMap<String,String>();body.put("creatorId",existing.get());
            var source=com.qingfeng.music.server.security.GuestDraftAssociation.source(request,existing.get());
            if(source!=null)body.put("associatedGuestCreatorId",source);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
        }
        return sessionResponse(identity.create(),request);
    }
    public record RecoveryRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=100)String code){}
    @PostMapping("/session/recover") public ResponseEntity<Map<String,String>> recover(HttpServletRequest request,@jakarta.validation.Valid @RequestBody RecoveryRequest body){return sessionResponse(identity.recover(body.code()),request);}
    @PostMapping("/session/logout")public ResponseEntity<Map<String,Boolean>> logout(HttpServletRequest request){
        identity.logout(CreatorSessionFilter.token(request));
        var temporary=request.getSession(false);if(temporary!=null)temporary.invalidate();
        var expired=ResponseCookie.from("QF_SESSION","").httpOnly(true).secure(request.isSecure()).sameSite("Lax").path("/api").maxAge(Duration.ZERO).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE,expired.toString()).body(Map.of("loggedOut",true));
    }
    private ResponseEntity<Map<String,String>> sessionResponse(IdentityApi.Session created,HttpServletRequest request){
        var cookie=ResponseCookie.from("QF_SESSION",created.token()).httpOnly(true).secure(request.isSecure()).sameSite("Lax").path("/api").maxAge(Duration.ofDays(30)).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE,cookie.toString()).body(Map.of("creatorId",created.creatorId()));
    }
}
