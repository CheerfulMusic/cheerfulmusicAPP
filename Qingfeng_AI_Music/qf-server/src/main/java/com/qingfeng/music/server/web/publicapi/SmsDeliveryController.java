package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.server.security.SmsBridgeService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"})
public class SmsDeliveryController {
    public static final String PATH="/api/public/auth/sms/deliver";
    private final SmsBridgeService service;
    public SmsDeliveryController(SmsBridgeService service){this.service=service;}
    @PostMapping(PATH) public ResponseEntity<Map<String,Object>> deliver(HttpServletRequest request)throws IOException{
        service.deliver(Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)),request.getContentType(),request.getContentLengthLong(),request.getInputStream());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("success",true));
    }
    @ExceptionHandler(SmsBridgeService.Failure.class) ResponseEntity<Map<String,Object>> failure(SmsBridgeService.Failure error){
        var response=ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore());if(error.status()==429)response.header("Retry-After","60");
        return response.body(Map.of("code",error.code(),"message","SMS delivery request could not be completed"));
    }
}
