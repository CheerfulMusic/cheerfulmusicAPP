package com.qingfeng.music.server.config;

import com.qingfeng.music.server.web.MediaCdnSigner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * 只读装配 CDN 签名配置。默认关闭（qingfeng.cdn.enabled=false），不修改 application.yml，
 * 不改动 StorageConfiguration、任何存储实现与文件路由。
 *
 * <p>KEY 等私密值只能出现在服务器私有配置中，禁止写入本仓库或任何日志。
 */
@Configuration(proxyBeanMethods=false)
@Profile({"mysql","test"})
public class CdnConfiguration {
    @Bean
    MediaCdnSigner mediaCdnSigner(Environment env){
        return new MediaCdnSigner(
            Boolean.parseBoolean(env.getProperty("qingfeng.cdn.enabled","false")),
            env.getProperty("qingfeng.cdn.domain",""),
            env.getProperty("qingfeng.cdn.key-primary",""),
            env.getProperty("qingfeng.cdn.key-backup",""),
            seconds(env.getProperty("qingfeng.cdn.ttl-seconds","600")),
            env.getProperty("qingfeng.cdn.uid","0"),
            env.getProperty("qingfeng.oss.prefix","qingfeng/audio/"),
            env.getProperty("qingfeng.oss.cover-prefix","qingfeng/covers/"));
    }
    private static long seconds(String value){
        try{return Long.parseLong(value.trim());}
        catch(RuntimeException e){return 600;}
    }
}
