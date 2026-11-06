package com.qingfeng.music.server.security;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Authenticated, bounded delivery adapter for Logto's HTTP SMS connector. No plaintext OTP storage. */
public final class SmsBridgeService {
    private static final int MAX_BODY=2048;
    private static final Set<String> TYPES=Set.of("Register","SignIn","ForgotPassword","Generic","UserPermissionValidation","BindNewIdentifier","OrganizationInvitation","MfaVerification","BindMfa");
    private final boolean enabled;
    private final byte[] authorization;
    private final SmsOtpSender sender;
    private final Clock clock;
    private final int hourlyLimit;
    private final ObjectMapper json=new ObjectMapper();
    private final Map<String,Bucket> limits=new HashMap<>();
    private final Map<String,Receipt> receipts=new HashMap<>();
    private static final class Bucket { final long until; int count; Bucket(long until){this.until=until;} }
    private static final class Receipt { final long until; int state; Receipt(long until){this.until=until;} }
    public static final class Failure extends RuntimeException {
        private final int status; private final String code;
        private Failure(int status,String code){super("SMS delivery request rejected");this.status=status;this.code=code;}
        public int status(){return status;}public String code(){return code;}
    }

    public SmsBridgeService(boolean enabled,String secret,SmsOtpSender sender,int hourlyLimit,Clock clock){
        this.enabled=enabled;this.sender=sender;this.clock=clock;this.hourlyLimit=hourlyLimit;
        if(enabled&&(sender==null||!secret.matches("[A-Za-z0-9_-]{43,128}")||hourlyLimit<1||hourlyLimit>2000))throw new IllegalStateException("Incomplete SMS bridge configuration");
        this.authorization=hash("Bearer "+secret);
    }
    public void deliver(List<String> headers,String contentType,long declaredLength,InputStream input){
        if(!enabled)throw new Failure(503,"SMS_DELIVERY_UNAVAILABLE");
        String header=headers.size()==1?headers.get(0):"";
        // Hash both sides first, so comparison always operates on equal-length digests.
        if(header.length()>256||!MessageDigest.isEqual(authorization,hash(header)))throw new Failure(401,"SMS_DELIVERY_UNAUTHORIZED");
        if(contentType==null||!contentType.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?"))throw new Failure(415,"INVALID_SMS_DELIVERY");
        if(declaredLength>MAX_BODY)throw new Failure(413,"INVALID_SMS_DELIVERY");
        String digits,code;
        try{
            byte[] bytes=input.readNBytes(MAX_BODY+1);
            if(bytes.length>MAX_BODY)throw new Failure(413,"INVALID_SMS_DELIVERY");
            var body=json.readTree(bytes);
            if(!body.isObject()||!body.path("to").isString()||!body.path("type").isString()||!body.path("payload").path("code").isString())throw new Failure(400,"INVALID_SMS_DELIVERY");
            String to=body.path("to").asText();code=body.path("payload").path("code").asText();
            if(!to.matches("\\+?[1-9][0-9]{7,14}")||!code.matches("[0-9]{6}")||!TYPES.contains(body.path("type").asText()))throw new Failure(400,"INVALID_SMS_DELIVERY");
            digits=to.startsWith("+")?to.substring(1):to;
            if(digits.startsWith("86")&&!digits.matches("861[3-9][0-9]{9}"))throw new Failure(400,"INVALID_SMS_DELIVERY");
        }catch(Failure error){throw error;}
        catch(Exception error){throw new Failure(400,"INVALID_SMS_DELIVERY");}
        String key=hex(hash(digits+":"+code));
        Receipt receipt=reserve(digits,key);
        if(receipt==null)return; // A retry of an acknowledged delivery must not incur another charge.
        try{
            sender.send(digits,code);
            synchronized(this){receipt.state=1;}
        }catch(RuntimeException error){
            // Retain a failed/ambiguous receipt until expiry; automatic retry could send and charge twice.
            synchronized(this){receipt.state=-1;}
            throw new Failure(502,"SMS_DELIVERY_UNAVAILABLE");
        }
    }
    private synchronized Receipt reserve(String phone,String key){
        long now=clock.millis();limits.entrySet().removeIf(e->e.getValue().until<=now);receipts.entrySet().removeIf(e->e.getValue().until<=now);
        var old=receipts.get(key);if(old!=null){if(old.state==1)return null;throw new Failure(503,"SMS_DELIVERY_UNAVAILABLE");}
        if(limits.size()>10000||receipts.size()>10000)throw new Failure(429,"SMS_DELIVERY_RATE_LIMITED");
        String phoneHash=hex(hash(phone));
        take("minute:"+phoneHash,1,60000,now);take("hour:"+phoneHash,5,3600000,now);take("global",hourlyLimit,3600000,now);
        var receipt=new Receipt(now+600000);receipts.put(key,receipt);return receipt;
    }
    private void take(String key,int maximum,long window,long now){var b=limits.computeIfAbsent(key,k->new Bucket(now+window));if(b.count++>=maximum)throw new Failure(429,"SMS_DELIVERY_RATE_LIMITED");}
    private static byte[] hash(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String hex(byte[] value){return HexFormat.of().formatHex(value);}
}
