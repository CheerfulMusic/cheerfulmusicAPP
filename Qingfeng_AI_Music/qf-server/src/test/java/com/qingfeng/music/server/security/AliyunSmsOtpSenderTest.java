package com.qingfeng.music.server.security;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.http.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class AliyunSmsOtpSenderTest {
    private static final class Wire extends IHttpClient {
        final List<HttpRequest> sent=new ArrayList<>();String result="{\"ResponseCode\":\"OK\",\"MessageId\":\"fixture\"}";int status=200;
        protected void init(HttpClientConfig config){}
        public HttpResponse syncInvoke(HttpRequest request){
            sent.add(request);var response=new HttpResponse();response.setStatus(status);
            response.setHttpContent(result.getBytes(StandardCharsets.UTF_8),"UTF-8",FormatType.JSON);return response;
        }
        public Future<HttpResponse> asyncInvoke(HttpRequest request,CallBack callback){throw new UnsupportedOperationException();}
        public void ignoreSSLCertificate(){throw new AssertionError("TLS verification must remain enabled");}
        public void restoreSSLCertificate(){}public boolean isSingleton(){return false;}public void close(){}
    }
    private static DefaultAcsClient client(String region,Wire wire){var client=AliyunSmsOtpSender.client(region,"fixture-access-id","fixture-secret");client.setHttpClient(wire);return client;}
    private static Map<String,String> fields(HttpRequest request)throws Exception{
        Map<String,String> result=new HashMap<>();var uri=URI.create(request.getSysUrl());
        for(String raw:List.of(Objects.toString(uri.getRawQuery(),""),request.getHttpContentString()))for(String pair:raw.split("&")){
            String[] parts=pair.split("=",2);if(parts.length==2)result.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),URLDecoder.decode(parts[1],StandardCharsets.UTF_8));
        }
        return result;
    }
    @Test void officialSdkSignsGlobePostWithFixedOtpTextAndOptionalNumericChannel()throws Exception{
        var wire=new Wire();try(var sender=new AliyunSmsOtpSender(client("ap-southeast-1",wire),null,"Cheerful","3790","","")){
            sender.send("447700900123","123456");var request=wire.sent.get(0);var data=fields(request);
            assertEquals("https",URI.create(request.getSysUrl()).getScheme());assertEquals("dysmsapi.ap-southeast-1.aliyuncs.com",URI.create(request.getSysUrl()).getHost());assertEquals(MethodType.POST,request.getSysMethod());
            assertEquals("SendMessageToGlobe",data.get("Action"));assertEquals("2018-05-01",data.get("Version"));assertEquals("HMAC-SHA1",data.get("SignatureMethod"));assertFalse(data.get("Signature").isBlank());
            assertEquals("447700900123",data.get("To"));assertEquals("Cheerful",data.get("From"));assertEquals("OTP",data.get("Type"));assertEquals("3790",data.get("ChannelId"));
            assertEquals("Cheerful Music verification code: 123456. Valid for 10 minutes. Do not share this code.",data.get("Message"));
            assertFalse(request.getSysUrl().contains("123456"));assertFalse(request.getSysUrl().contains("Message="));assertFalse(data.containsKey("SignName"));assertFalse(data.containsKey("TemplateCode"));
        }
    }
    @Test void mainlandMasReceivesExactlyLogtoCodeAndNeverGeneratesAReplacement()throws Exception{
        var wire=new Wire();wire.result="{\"Code\":\"OK\",\"Success\":true}";
        try(var sender=new AliyunSmsOtpSender(null,client("cn-hangzhou",wire),"Cheerful","","fixture-sign","fixture-template")){
            sender.send("8613800000000","654321");var request=wire.sent.get(0);var data=fields(request);
            assertEquals("dypnsapi.aliyuncs.com",URI.create(request.getSysUrl()).getHost());assertEquals("SendSmsVerifyCode",data.get("Action"));assertEquals("2017-05-25",data.get("Version"));
            assertEquals("86",data.get("CountryCode"));assertEquals("13800000000",data.get("PhoneNumber"));assertEquals("fixture-sign",data.get("SignName"));assertEquals("fixture-template",data.get("TemplateCode"));
            var template=new ObjectMapper().readTree(data.get("TemplateParam"));assertEquals("654321",template.path("code").asText());assertEquals("10",template.path("min").asText());
            assertEquals("0",data.get("AutoRetry"));assertEquals("false",data.get("ReturnVerifyCode"));assertEquals("600",data.get("ValidTime"));assertFalse(request.getSysUrl().contains("654321"));
        }
    }
    @Test void providerFailuresAreNotRetriedAndNeverExposeResponseOtpOrSecret(){
        var wire=new Wire();wire.result="{\"ResponseCode\":\"REJECTED\",\"ResponseDescription\":\"private OTP 123456 fixture-secret\"}";
        try(var sender=new AliyunSmsOtpSender(client("ap-southeast-1",wire),null,"Cheerful","","","")){
            var error=assertThrows(IllegalStateException.class,()->sender.send("447700900124","123456"));
            assertEquals("SMS delivery unavailable",error.getMessage());assertNull(error.getCause());assertEquals(1,wire.sent.size());
        }
        var failing=new Wire();failing.status=500;failing.result="{\"Code\":\"InternalError\",\"Message\":\"provider secret\"}";
        try(var sender=new AliyunSmsOtpSender(client("ap-southeast-1",failing),null,"Cheerful","","","")){
            assertThrows(IllegalStateException.class,()->sender.send("447700900125","123456"));assertEquals(1,failing.sent.size());
        }
    }
    @Test void disabledRouteInvalidRegionAndInvalidSenderCannotSend(){
        var wire=new Wire();try(var sender=new AliyunSmsOtpSender(client("ap-southeast-1",wire),null,"Cheerful","","","")){
            assertThrows(IllegalStateException.class,()->sender.send("8613800000000","123456"));
            assertThrows(IllegalArgumentException.class,()->sender.send("+447700900123","123456"));
            assertThrows(IllegalArgumentException.class,()->sender.send("447700900123","arbitrary text"));assertTrue(wire.sent.isEmpty());
        }
        assertThrows(IllegalStateException.class,()->new AliyunSmsOtpSender(null,null,"Cheerful","UK_SMS","",""));
    }
}
