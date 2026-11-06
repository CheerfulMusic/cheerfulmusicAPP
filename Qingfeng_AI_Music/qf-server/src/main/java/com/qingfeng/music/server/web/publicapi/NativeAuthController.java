package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.*;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Same-origin login endpoints; provider cookies, verification IDs and tokens stay on the server. */
@RestController @Profile({"mysql","test"})
@RequestMapping("/api/public/auth/native")
public class NativeAuthController {
    private static final String PENDING=NativeAuthController.class.getName()+".pending";
    private static final Pattern EMAIL=Pattern.compile("^[^\\s@\\p{Cntrl}]+@[^\\s@\\p{Cntrl}]+\\.[^\\s@\\p{Cntrl}]+$");
    private static final Pattern PHONE=Pattern.compile("\\+[1-9][0-9]{7,14}");
    private final ObjectProvider<LogtoNativeClient> clients;
    private final IdentityApi identity;
    private final LogtoIdentityService identities;
    private final boolean emailEnabled,phoneEnabled,phoneInternationalEnabled;
    private final String issuer;
    private final Map<String,Bucket> limits=new HashMap<>();
    private static final class Bucket { long until; int count; Bucket(long until){this.until=until;} }
    private static final class Pending {
        final String creator,tokenHash,channel,value,purpose,verification;
        final long started=System.currentTimeMillis();
        final LogtoNativeClient.Interaction flow;
        int attempts;
        Pending(String creator,String token,String channel,String value,String purpose,String verification,LogtoNativeClient.Interaction flow){
            this.creator=creator;this.tokenHash=hash(token);this.channel=channel;this.value=value;this.purpose=purpose;this.verification=verification;this.flow=flow;
        }
    }
    // Deliberately ordinary classes: generated record toString() must never expose submitted passwords or codes.
    public static final class Credentials { public String email,password,code,phone; }
    public static final class CodeRequest { public String channel,identifier,purpose; }
    private static final class Failure extends RuntimeException {
        final int status; final String code; long retryAfter=60;
        Failure(int status,String code,String message){super(message);this.status=status;this.code=code;}
    }

    public NativeAuthController(ObjectProvider<LogtoNativeClient> clients,IdentityApi identity,LogtoIdentityService identities,
        @Value("${qingfeng.auth.logto.email-enabled:false}")boolean emailEnabled,
        @Value("${qingfeng.auth.logto.phone-enabled:false}")boolean phoneEnabled,
        @Value("${qingfeng.auth.logto.phone-international-enabled:false}")boolean phoneInternationalEnabled,
        @Value("${qingfeng.auth.logto.issuer:}")String issuer){
        this.clients=clients;this.identity=identity;this.identities=identities;this.emailEnabled=emailEnabled;this.phoneEnabled=phoneEnabled;this.phoneInternationalEnabled=phoneInternationalEnabled;this.issuer=issuer;
    }

    @GetMapping("/status") public ResponseEntity<Map<String,Object>> status(HttpServletRequest request){
        boolean password=false,email=false,phone=false,reset=false;
        var client=clients.getIfAvailable();
        if(client!=null)try{var c=client.capabilities();password=emailEnabled&&c.emailPassword();email=emailEnabled&&c.emailCode();phone=phoneEnabled&&c.phone();reset=emailEnabled&&c.reset();}catch(RuntimeException error){throw unavailable();}
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("emailPasswordEnabled",password);result.put("emailCodeEnabled",email);result.put("phoneEnabled",phone);result.put("phoneInternationalEnabled",phoneInternationalEnabled);result.put("passwordResetEnabled",reset);
        var profile=identity.resolve(CreatorSessionFilter.token(request)).flatMap(identities::profile);
        result.put("authenticated",profile.isPresent());
        profile.ifPresent(p->{if(p.email()!=null)result.put("email",p.email());if(p.phoneMasked()!=null)result.put("phoneMasked",p.phoneMasked());if(p.name()!=null)result.put("name",p.name());});
        return ok(result);
    }

    @PostMapping("/password/sign-in") public ResponseEntity<Map<String,Object>> password(@RequestBody Credentials input,HttpServletRequest request,HttpServletResponse response){
        String email=email(input.email);password(input.password,false);boundCreator(request);rate(request,email,false);
        var client=client();if(!emailEnabled||!client.capabilities().emailPassword())throw unavailable();
        HttpSession browser=request.getSession(true);
        synchronized(browser){
            browser.removeAttribute(PENDING);
            var flow=client.begin("SignIn");
            try{
                client.identify(flow,client.password(flow,email,input.password));
                return finish(request,response,client,flow,"email",email);
            }catch(LogtoNativeClient.ProviderFailure error){throw failure(error,false);}
        }
    }

    @PostMapping("/code") public ResponseEntity<Map<String,Object>> sendCode(@RequestBody CodeRequest input,HttpServletRequest request){
        String channel=input.channel,purpose=input.purpose;
        if(!Set.of("email","phone").contains(Objects.toString(channel,""))||!Set.of("register","reset","sign-in").contains(Objects.toString(purpose,""))
            ||("phone".equals(channel)&&!"sign-in".equals(purpose)))throw invalid();
        String value="email".equals(channel)?email(input.identifier):phone(input.identifier);
        if("phone".equals(channel)&&!phoneInternationalEnabled&&!value.startsWith("+86"))throw new Failure(400,"LOGIN_REGION_UNAVAILABLE","当前暂不支持向该地区发送验证码");
        String creator=boundCreator(request);rate(request,value,true);
        var client=client();var caps=client.capabilities();
        if("email".equals(channel)?!emailEnabled||!caps.emailCode()||("reset".equals(purpose)&&!caps.reset()):!phoneEnabled||!caps.phone())throw unavailable();
        HttpSession browser=request.getSession(true);browser.setMaxInactiveInterval(600);
        synchronized(browser){
            String event="register".equals(purpose)?"Register":"reset".equals(purpose)?"ForgotPassword":"SignIn";
            try{
                var flow=client.begin(event);
                String verification=client.sendCode(flow,channel,value,event);
                browser.setAttribute(PENDING,new Pending(creator,CreatorSessionFilter.token(request),channel,value,purpose,verification,flow));
                return ok(Map.of("sent",true,"resendAfter",60));
            }catch(LogtoNativeClient.ProviderFailure error){throw failure(error,true);}
        }
    }

    @PostMapping("/register") public ResponseEntity<Map<String,Object>> register(@RequestBody Credentials input,HttpServletRequest request,HttpServletResponse response){
        String value=email(input.email);password(input.password,true);code(input.code);rate(request,value,false);
        HttpSession browser=session(request);
        synchronized(browser){
            Pending pending=pending(request,"email",value,"register");var client=client();
            try{
                client.verifyCode(pending.flow,"email",value,pending.verification,input.code);
                client.registrationPassword(pending.flow,input.password);
                client.identify(pending.flow,pending.verification);
                return finish(request,response,client,pending.flow,"email",value);
            }catch(LogtoNativeClient.ProviderFailure error){throw failure(error,true);}
        }
    }

    @PostMapping("/password/reset") public ResponseEntity<Map<String,Object>> reset(@RequestBody Credentials input,HttpServletRequest request){
        String value=email(input.email);password(input.password,true);code(input.code);rate(request,value,false);
        HttpSession browser=session(request);
        synchronized(browser){
            Pending pending=pending(request,"email",value,"reset");var client=client();
            try{
                client.verifyCode(pending.flow,"email",value,pending.verification,input.code);
                client.identify(pending.flow,pending.verification);
                client.resetPassword(pending.flow,input.password);
                identities.revokeSessionsForVerifiedEmail(value);
                browser.removeAttribute(PENDING);
                return ok(Map.of("reset",true));
            }catch(LogtoNativeClient.ProviderFailure error){throw failure(error,true);}
        }
    }

    @PostMapping("/phone/sign-in") public ResponseEntity<Map<String,Object>> phoneSignIn(@RequestBody Credentials input,HttpServletRequest request,HttpServletResponse response){
        return codeSignIn("phone",phone(input.phone),input.code,request,response);
    }
    @PostMapping("/email/sign-in") public ResponseEntity<Map<String,Object>> emailSignIn(@RequestBody Credentials input,HttpServletRequest request,HttpServletResponse response){
        return codeSignIn("email",email(input.email),input.code,request,response);
    }
    private ResponseEntity<Map<String,Object>> codeSignIn(String channel,String value,String code,HttpServletRequest request,HttpServletResponse response){
        code(code);rate(request,value,false);HttpSession browser=session(request);
        synchronized(browser){
            Pending pending=pending(request,channel,value,"sign-in");var client=client();
            try{
                client.verifyCode(pending.flow,channel,value,pending.verification,code);
                try{client.identify(pending.flow,pending.verification);}
                catch(LogtoNativeClient.ProviderFailure missing){
                    if(!"user.user_not_exist".equals(missing.code()))throw missing;
                    // SignIn -> Register preserves the already verified identifier; no unverified profile is accepted.
                    client.switchToRegister(pending.flow);client.identify(pending.flow,pending.verification);
                }
                return finish(request,response,client,pending.flow,channel,value);
            }catch(LogtoNativeClient.ProviderFailure error){throw failure(error,true);}
        }
    }

    private ResponseEntity<Map<String,Object>> finish(HttpServletRequest request,HttpServletResponse response,LogtoNativeClient client,LogtoNativeClient.Interaction flow,String channel,String value){
        String creator=boundCreator(request);var verified=client.complete(flow);
        // The verified token/userinfo must prove the exact identifier entered in this browser flow.
        if("email".equals(channel)?verified.email()==null||!value.equalsIgnoreCase(verified.email()):verified.phone()==null||!value.equals("+"+verified.phone().replaceFirst("^\\+","")))throw new Failure(401,"LOGIN_INVALID_CREDENTIALS","身份验证未通过，请重试");
        IdentityApi.Session signed;
        try{signed=identities.signIn(CreatorSessionFilter.token(request),creator,issuer,verified.subject(),verified.email(),verified.phone(),verified.name());}
        catch(DomainException conflict){throw new Failure(409,"LOGIN_ACCOUNT_CONFLICT","当前账号无法合并，请退出后重新登录");}
        GuestDraftAssociation.prepare(request,creator,signed.creatorId());
        GuestDraftAssociation.rotate(request);
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from("QF_SESSION",signed.token()).httpOnly(true).secure(request.isSecure()).sameSite("Lax").path("/api").maxAge(Duration.ofDays(30)).build().toString());
        return ok(Map.of("authenticated",true));
    }
    private Pending pending(HttpServletRequest request,String channel,String value,String purpose){
        String creator=boundCreator(request);var browser=session(request);Object saved=browser.getAttribute(PENDING);
        if(!(saved instanceof Pending p)||System.currentTimeMillis()-p.started>600000||!p.creator.equals(creator)||!p.tokenHash.equals(hash(CreatorSessionFilter.token(request)))
            ||!p.channel.equals(channel)||!p.value.equals(value)||!p.purpose.equals(purpose)||p.attempts++>=5){browser.removeAttribute(PENDING);throw expired();}
        return p;
    }
    private String boundCreator(HttpServletRequest request){return identity.resolve(CreatorSessionFilter.token(request)).orElseThrow(NativeAuthController::expired);}
    private static HttpSession session(HttpServletRequest request){var session=request.getSession(false);if(session==null)throw expired();return session;}
    private LogtoNativeClient client(){var client=clients.getIfAvailable();if(client==null)throw unavailable();return client;}
    private synchronized void rate(HttpServletRequest request,String identifier,boolean sending){
        long now=System.currentTimeMillis();limits.entrySet().removeIf(e->e.getValue().until<now);
        if(limits.size()>10000)throw limited();
        take((sending?"send-ip:":"verify-ip:")+request.getRemoteAddr(),sending?20:60,60000,now);
        take((sending?"send-id:":"verify-id:")+hash(identifier.toLowerCase(Locale.ROOT)),sending?5:15,3600000,now);
        if(sending){take("send:"+hash(identifier.toLowerCase(Locale.ROOT)),1,60000,now);take("global:send",200,3600000,now);}
    }
    private void take(String key,int max,long window,long now){var bucket=limits.computeIfAbsent(key,k->new Bucket(now+window));if(bucket.count>=max){var failure=limited();failure.retryAfter=Math.max(1,(bucket.until-now+999)/1000);throw failure;}bucket.count++;}
    private static String email(String value){if(value==null||value.length()>254||!EMAIL.matcher(value.trim()).matches())throw invalid();return value.trim();}
    private static String phone(String value){if(value==null||!PHONE.matcher(value).matches())throw invalid();return value;}
    private static void password(String value,boolean creating){if(value==null||value.length()<(creating?8:1)||value.length()>128)throw invalid();}
    private static void code(String value){if(value==null||!value.matches("[0-9]{6}"))throw invalid();}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Objects.toString(value,"").getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static Failure invalid(){return new Failure(400,"INVALID_LOGIN_INPUT","请检查邮箱、手机号、密码或验证码格式");}
    private static Failure expired(){return new Failure(409,"LOGIN_FLOW_EXPIRED","登录已过期，请重新获取验证码或刷新后重试");}
    private static Failure unavailable(){return new Failure(503,"LOGIN_UNAVAILABLE","此登录方式暂不可用，请稍后重试");}
    private static Failure limited(){return new Failure(429,"LOGIN_RATE_LIMITED","操作较频繁，请稍后重试");}
    private static Failure failure(LogtoNativeClient.ProviderFailure error,boolean code){
        org.slf4j.LoggerFactory.getLogger(NativeAuthController.class).warn("Native authentication rejected: status={}, code={}",error.status(),error.code().replaceAll("[^a-zA-Z0-9_.-]","_"));
        if(error.status()>=500||error.status()==403)return unavailable();
        if(Set.of("user.email_already_in_use","user.phone_already_in_use").contains(error.code()))return new Failure(409,"LOGIN_ACCOUNT_EXISTS","该账号已注册，请登录或找回密码");
        if(error.status()==429||error.code().contains("attempts"))return limited();
        if(error.code().startsWith("password.")||error.code().contains("password_policy")||error.code().contains("same_password"))return new Failure(400,"LOGIN_PASSWORD_REJECTED","密码不符合要求，请尝试其他密码");
        if(code&&Set.of("session.not_found","session.verification_failed","session.verification_session_not_found").contains(error.code()))return expired();
        if(code&&!error.code().startsWith("verification_code."))return unavailable();
        return new Failure(code?400:401,code?"LOGIN_CODE_INVALID":"LOGIN_INVALID_CREDENTIALS",code?"验证码无效或操作未完成，请检查后重试":"邮箱或密码不正确，请重试");
    }
    @ExceptionHandler(Failure.class) ResponseEntity<Map<String,Object>> failure(Failure error){
        var builder=ResponseEntity.status(error.status).cacheControl(CacheControl.noStore());if(error.status==429)builder.header("Retry-After",Long.toString(error.retryAfter));
        return builder.body(Map.of("code",error.code,"message",error.getMessage()));
    }
    @ExceptionHandler(LogtoNativeClient.ProviderFailure.class) ResponseEntity<Map<String,Object>> providerFailure(LogtoNativeClient.ProviderFailure error){return failure(failure(error,false));}
    private static ResponseEntity<Map<String,Object>> ok(Map<String,Object> body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
