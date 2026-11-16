package com.qingfeng.music.server.config;

import com.qingfeng.music.server.security.*;
import com.aliyuncs.DefaultAcsClient;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
public class SmsBridgeConfiguration {
    @Bean SmsDestinationPolicy smsDestinationPolicy(Environment env){
        String prefix="qingfeng.auth.sms-bridge.";
        return new SmsDestinationPolicy(env.getProperty("qingfeng.auth.logto.phone-enabled",Boolean.class,false),
            env.getProperty("qingfeng.auth.logto.phone-international-enabled",Boolean.class,false),
            env.getProperty(prefix+"enabled",Boolean.class,false),
            env.getProperty(prefix+"cn.enabled",Boolean.class,false),
            env.getProperty(prefix+"international.enabled",Boolean.class,false),
            value(env,prefix+"international.allowed-regions"));
    }
    @Bean(destroyMethod="close") SmsOtpSender smsOtpSender(Environment env,SmsDestinationPolicy destinations){
        String prefix="qingfeng.auth.sms-bridge.";
        if(!env.getProperty(prefix+"enabled",Boolean.class,false))return (phone,code)->{throw new IllegalStateException("SMS delivery unavailable");};
        boolean overseas=env.getProperty(prefix+"international.enabled",Boolean.class,false),cn=env.getProperty(prefix+"cn.enabled",Boolean.class,false);
        if(!overseas&&!cn)throw new IllegalStateException("No SMS delivery provider configured");
        DefaultAcsClient international=overseas?AliyunSmsOtpSender.client("ap-southeast-1",value(env,prefix+"international.access-key-id"),value(env,prefix+"international.access-key-secret")):null;
        DefaultAcsClient mainland=cn?AliyunSmsOtpSender.client("cn-hangzhou",value(env,prefix+"cn.access-key-id"),value(env,prefix+"cn.access-key-secret")):null;
        return new AliyunSmsOtpSender(international,mainland,value(env,prefix+"international.sender-id"),env.getProperty(prefix+"international.channel-id",""),value(env,prefix+"cn.sign-name"),value(env,prefix+"cn.template-code"),destinations);
    }
    @Bean SmsBridgeService smsBridgeService(Environment env,SmsOtpSender sender,SmsDestinationPolicy destinations){
        String p="qingfeng.auth.sms-bridge.";
        return new SmsBridgeService(env.getProperty(p+"enabled",Boolean.class,false),value(env,p+"bearer-secret"),sender,
            env.getProperty(p+"hourly-limit",Integer.class,200),env.getProperty(p+"ip-hourly-limit",Integer.class,50),Clock.systemUTC(),destinations);
    }
    private static String value(Environment env,String key){return env.getProperty(key,"");}
}
