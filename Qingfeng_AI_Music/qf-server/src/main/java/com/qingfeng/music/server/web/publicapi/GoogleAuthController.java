package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"mysql","test"})
@RequestMapping("/api/public/auth/google")
public class GoogleAuthController {
    private final ObjectProvider<GoogleOAuthFlow> flow;
    private final IdentityApi identity;
    public GoogleAuthController(ObjectProvider<GoogleOAuthFlow> flow,IdentityApi identity){this.flow=flow;this.identity=identity;}
    @GetMapping("/status")public ResponseEntity<Map<String,Object>> status(HttpServletRequest request){
        Map<String,Object> result=new LinkedHashMap<>();result.put("enabled",flow.getIfAvailable()!=null);result.put("authenticated",false);
        if(flow.getIfAvailable()==null)return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        identity.resolve(CreatorSessionFilter.token(request)).flatMap(identity::googleIdentity).ifPresent(profile->{
            result.put("authenticated",true);if(profile.email()!=null)result.put("email",profile.email());if(profile.name()!=null)result.put("name",profile.name());
        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @PostMapping("/start")public ResponseEntity<Map<String,String>> start(HttpServletRequest request,HttpServletResponse response){
        var google=flow.getIfAvailable();
        if(google==null)return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("code","GOOGLE_LOGIN_DISABLED","message","Google登录暂未启用，请继续使用作品找回码"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("authorizationUrl",google.start(request,response)));
    }
}
