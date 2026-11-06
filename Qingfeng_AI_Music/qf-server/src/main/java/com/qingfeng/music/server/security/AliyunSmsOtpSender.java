package com.qingfeng.music.server.security;

import com.aliyuncs.*;
import com.aliyuncs.auth.SignatureVersion;
import com.aliyuncs.http.*;
import com.aliyuncs.profile.DefaultProfile;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** Fixed official Aliyun endpoints; SDK signs requests. Secrets and provider response bodies are never logged. */
public final class AliyunSmsOtpSender implements SmsOtpSender {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AliyunSmsOtpSender.class);
    private static final Set<String> ERROR_CODES=Set.of("InvalidAccessKeyId.NotFound","InvalidAccessKeyId","SignatureDoesNotMatch","Forbidden.RAM","Forbidden","AccessDenied","NoPermission","UnauthorizedOperation","InvalidParameter","MissingParameter","InvalidRegionId","isv.BUSINESS_LIMIT_CONTROL","isv.MOBILE_NUMBER_ILLEGAL","isv.INVALID_PARAMETERS","isv.SMS_CONTENT_ILLEGAL","isv.SMS_SIGNATURE_ILLEGAL","isv.DENY_IP_RANGE","isp.RAM_PERMISSION_DENY","isp.SYSTEM_ERROR","SYSTEM_ERROR");
    private static final class Rejected extends RuntimeException {}
    private final DefaultAcsClient international, mainland;
    private final String senderId,channelId,signName,templateCode;
    private final ObjectMapper json=new ObjectMapper();

    public AliyunSmsOtpSender(DefaultAcsClient international,DefaultAcsClient mainland,String senderId,String channelId,String signName,String templateCode){
        this.international=international;this.mainland=mainland;this.senderId=senderId;this.channelId=channelId;this.signName=signName;this.templateCode=templateCode;
        if(international!=null&&!(senderId.matches("[0-9]{1,15}")||senderId.matches("(?=.*[A-Za-z])[A-Za-z0-9]{1,11}")))throw new IllegalStateException("Invalid SMS sender configuration");
        if(!channelId.isEmpty()&&!channelId.matches("[0-9]{1,32}"))throw new IllegalStateException("Invalid SMS channel configuration");
        if(mainland!=null&&(signName.isBlank()||templateCode.isBlank()))throw new IllegalStateException("Incomplete mainland SMS configuration");
    }
    public static DefaultAcsClient client(String region,String accessKeyId,String accessKeySecret){
        if(accessKeyId.isBlank()||accessKeySecret.isBlank())throw new IllegalStateException("Incomplete SMS credentials");
        var profile=DefaultProfile.getProfile(region,accessKeyId,accessKeySecret);
        profile.setCloseTrace(true);profile.setLogger(org.slf4j.helpers.NOPLogger.NOP_LOGGER);
        var client=new DefaultAcsClient(profile);client.setAutoRetry(false);client.setMaxRetryNumber(0);client.setSignatureVersion(SignatureVersion.V1);
        return client;
    }
    public void send(String digits,String code){
        if(!digits.matches("[1-9][0-9]{7,14}")||!code.matches("[0-9]{6}"))throw new IllegalArgumentException("Invalid OTP delivery");
        boolean cn=digits.startsWith("86");
        DefaultAcsClient client=cn?mainland:international;
        if(client==null)throw new IllegalStateException("SMS delivery unavailable");
        var request=new CommonRequest();request.setSysMethod(MethodType.POST);request.setSysProtocol(ProtocolType.HTTPS);request.setSysAccept(FormatType.JSON);
        request.setSysSignatureVersion(SignatureVersion.V1);request.setSysConnectTimeout(3000);request.setSysReadTimeout(8000);
        if(cn){
            if(!digits.matches("861[3-9][0-9]{9}"))throw new IllegalArgumentException("Invalid mainland mobile number");
            request.setSysDomain("dypnsapi.aliyuncs.com");request.setSysRegionId("cn-hangzhou");request.setSysVersion("2017-05-25");request.setSysAction("SendSmsVerifyCode");
            body(request,Map.of("CountryCode","86","PhoneNumber",digits.substring(2),"SignName",signName,"TemplateCode",templateCode,
                "TemplateParam",json.writeValueAsString(Map.of("code",code,"min","10")),"CodeLength","6","ValidTime","600","Interval","60","ReturnVerifyCode","false","AutoRetry","0"));
        }else{
            request.setSysDomain("dysmsapi.ap-southeast-1.aliyuncs.com");request.setSysRegionId("ap-southeast-1");request.setSysVersion("2018-05-01");request.setSysAction("SendMessageToGlobe");
            body(request,Map.of("To",digits,"From",senderId,"Type","OTP","ValidityPeriod","600",
                "Message","Cheerful Music verification code: "+code+". Valid for 10 minutes. Do not share this code."));
            if(!channelId.isEmpty())request.putBodyParameter("ChannelId",channelId);
        }
        try{
            var response=client.getCommonResponse(request);
            var result=json.readTree(response.getData());
            String field=cn?"Code":"ResponseCode";
            if(response.getHttpStatus()!=200||!"OK".equals(result.path(field).asText())||(cn&&result.has("Success")&&!result.path("Success").asBoolean())){
                rejected(cn,result.path(field).asText());throw new Rejected();
            }
        }catch(Rejected error){throw new IllegalStateException("SMS delivery unavailable");}
        catch(com.aliyuncs.exceptions.ClientException error){
            rejected(cn,error.getErrCode());throw new IllegalStateException("SMS delivery unavailable");
        }catch(Exception error){
            // Provider exceptions may include signed URLs, response payloads or the OTP. Never attach their cause.
            rejected(cn,"");throw new IllegalStateException("SMS delivery unavailable");
        }
    }
    private static void rejected(boolean cn,String code){log.warn("OTP delivery rejected by {}: {}",cn?"Aliyun MAS":"Aliyun Globe",code!=null&&ERROR_CODES.contains(code)?code:"UNCLASSIFIED");}
    private static void body(CommonRequest request,Map<String,String> values){values.forEach(request::putBodyParameter);}
    public void close(){if(international!=null)international.shutdown();if(mainland!=null)mainland.shutdown();}
}
