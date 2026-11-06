package com.qingfeng.music.server.security;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:sms_hook;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.invite-gate.enabled=true","qingfeng.invite-gate.codes=fixture-beta-code","qingfeng.invite-gate.signing-key=fixture-beta-signing-secret-with-at-least-thirty-two-characters",
    "qingfeng.auth.sms-bridge.enabled=true","qingfeng.auth.sms-bridge.bearer-secret=fixture_bridge_secret_with_at_least_43_safe_characters"})
@ActiveProfiles("test")
class SmsDeliverySecurityIntegrationTest {
    private static final String BEARER="Bearer fixture_bridge_secret_with_at_least_43_safe_characters";
    private static final String PATH="/api/public/auth/sms/deliver";
    @Value("${local.server.port}") int port;
    @MockitoBean SmsOtpSender sender;
    private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private HttpResponse<String> request(String method,String path,String auth,String body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(auth!=null)request.header("Authorization",auth);
        if(body!=null)request.header("Content-Type","application/json");
        return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String body(String phone,String code){return "{\"to\":\""+phone+"\",\"type\":\"SignIn\",\"payload\":{\"code\":\""+code+"\",\"locale\":\"en\"},\"ip\":\"203.0.113.1\"}";}
    @Test void authenticatedLogtoCallbackNeedsNeitherBetaCookieCreatorNorCsrf()throws Exception{
        var response=request("POST",PATH,BEARER,body("447700900131","123456"));
        assertEquals(200,response.statusCode(),response.body());assertEquals("{\"success\":true}",response.body());verify(sender).send("447700900131","123456");
        assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));assertFalse(response.body().contains("123456"));
        assertEquals(200,request("POST",PATH,BEARER,body("+447700900131","123456")).statusCode());verify(sender,times(1)).send("447700900131","123456");
    }
    @Test void missingOrWrongBearerCannotReachSender()throws Exception{
        for(String auth:Arrays.asList(null,"Bearer wrong")){
            var response=request("POST",PATH,auth,body("447700900132","654321"));assertEquals(401,response.statusCode(),response.body());assertFalse(response.body().contains("654321"));assertFalse(response.body().contains("fixture_bridge"));
        }
        verifyNoInteractions(sender);
    }
    @Test void onlyExactPostCallbackBypassesGateAndCsrf()throws Exception{
        assertEquals(403,request("GET",PATH,BEARER,null).statusCode());
        assertEquals(403,request("POST",PATH+"/other",BEARER,body("447700900133","123456")).statusCode());
        assertEquals(403,request("POST","/api/public/auth/native/code",BEARER,"{\"channel\":\"phone\",\"identifier\":\"+447700900133\",\"purpose\":\"sign-in\"}").statusCode());
        assertEquals(403,request("POST","/api/public/auth/native/password/sign-in",BEARER,"{\"email\":\"fixture@example.test\",\"password\":\"fake-password\"}").statusCode());
        verifyNoInteractions(sender);
    }
    @Test void invalidOrOversizedPayloadIsRejectedWithoutSending()throws Exception{
        assertEquals(400,request("POST",PATH,BEARER,body("447700900134","arbitrary text")).statusCode());
        assertEquals(400,request("POST",PATH,BEARER,"{\"phone\":\"447700900134\",\"code\":\"123456\"}").statusCode());
        assertEquals(413,request("POST",PATH,BEARER," ".repeat(2049)).statusCode());verifyNoInteractions(sender);
    }
    @Test void providerErrorAndRetriesAreSanitizedAndCannotDoubleSend()throws Exception{
        doThrow(new IllegalStateException("secret-key raw provider OTP 123456")).when(sender).send("447700900135","123456");
        var response=request("POST",PATH,BEARER,body("447700900135","123456"));assertEquals(502,response.statusCode(),response.body());assertFalse(response.body().contains("secret-key"));assertFalse(response.body().contains("123456"));
        assertEquals(503,request("POST",PATH,BEARER,body("447700900135","123456")).statusCode());verify(sender,times(1)).send("447700900135","123456");
    }
}
