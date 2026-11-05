package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.server.security.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"mysql","test"})
@RequestMapping("/api/public/auth/logto")
public class LogtoAuthController {
    private final ObjectProvider<LogtoOAuthFlow> flow;
    private final LogtoIdentityService identities;
    private final IdentityApi identity;
    private final boolean emailEnabled,phoneEnabled,accountCenterEnabled;
    public LogtoAuthController(ObjectProvider<LogtoOAuthFlow> flow,LogtoIdentityService identities,IdentityApi identity,
        @Value("${qingfeng.auth.logto.email-enabled:false}")boolean emailEnabled,
        @Value("${qingfeng.auth.logto.phone-enabled:false}")boolean phoneEnabled,
        @Value("${qingfeng.auth.logto.account-center-enabled:false}")boolean accountCenterEnabled){
        this.flow=flow;this.identities=identities;this.identity=identity;
        this.emailEnabled=emailEnabled;this.phoneEnabled=phoneEnabled;this.accountCenterEnabled=accountCenterEnabled;
    }
    @GetMapping("/status")public ResponseEntity<Map<String,Object>> status(HttpServletRequest request){
        var logto=flow.getIfAvailable();boolean enabled=logto!=null;
        var profile=identity.resolve(CreatorSessionFilter.token(request)).flatMap(identities::profile);
        Map<String,Object> result=new LinkedHashMap<>();result.put("enabled",enabled);result.put("emailEnabled",enabled&&emailEnabled);result.put("phoneEnabled",enabled&&phoneEnabled);result.put("appleEnabled",enabled&&logto.supportsApple());result.put("authenticated",profile.isPresent());result.put("accountCenterEnabled",enabled&&accountCenterEnabled&&profile.isPresent());
        profile.ifPresent(value->{
            if(value.email()!=null)result.put("email",value.email());if(value.phoneMasked()!=null)result.put("phoneMasked",value.phoneMasked());if(value.name()!=null)result.put("name",value.name());
        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    public record StartRequest(String method,String loginHint){}
    @PostMapping("/start")public ResponseEntity<Map<String,String>> start(@RequestBody(required=false)StartRequest target,HttpServletRequest request,HttpServletResponse response){
        var logto=flow.getIfAvailable();
        if(logto==null)return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("code","LOGTO_LOGIN_DISABLED","message","Logto登录暂未启用"));
        String method=target==null?null:target.method();
        if("email".equals(method)&&!emailEnabled)throw new DomainException("LOGTO_EMAIL_UNAVAILABLE","邮箱验证码登录暂未启用");
        if("phone".equals(method)&&!phoneEnabled)throw new DomainException("LOGTO_PHONE_UNAVAILABLE","手机验证码登录暂未启用");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("authorizationUrl",logto.start(request,response,method,target==null?null:target.loginHint())));
    }
}
