package com.qingfeng.music.server.config;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.qingfeng.music.media.application.MediaFileStore;
import com.qingfeng.music.infrastructure.storage.*;
import java.nio.file.*;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
public class StorageConfiguration {
    private OSS client;
    @Bean MediaFileStore mediaFileStore(Environment env)throws IOException{
        String mode=env.getProperty("qingfeng.media.storage","local");
        if(!mode.equals("local")&&!mode.equals("oss"))throw new IllegalArgumentException("音频存储类型只能是local或oss");
        var local=new LocalMediaFileStore(Path.of(env.getProperty("qingfeng.media.directory","./data/audio")));
        OssMediaFileStore oss=null;String bucket=setting(env,"qingfeng.oss.bucket");
        if(mode.equals("oss")||!bucket.isBlank()){
            String endpoint=required(env,"qingfeng.oss.endpoint"),region=required(env,"qingfeng.oss.region");
            if(!endpoint.matches("https://oss-[a-z0-9-]+\\.aliyuncs\\.com"))throw new IllegalArgumentException("OSS必须使用HTTPS官方地域Endpoint");
            var configuration=new ClientBuilderConfiguration();configuration.setSignatureVersion(SignVersion.V4);configuration.setConnectionTimeout(5000);configuration.setSocketTimeout(20000);configuration.setMaxErrorRetry(0);
            client=OSSClientBuilder.create().endpoint(endpoint).region(region).credentialsProvider(new DefaultCredentialProvider(required(env,"qingfeng.oss.access-key-id"),required(env,"qingfeng.oss.access-key-secret"))).clientConfiguration(configuration).build();
            oss=new OssMediaFileStore(client,required(env,"qingfeng.oss.bucket"),env.getProperty("qingfeng.oss.prefix","qingfeng/audio/"),new LocalMediaFileStore(Path.of(env.getProperty("qingfeng.media.staging-directory","./data/staging"))));
        }
        return new RoutingMediaFileStore(local,oss,mode.equals("oss"));
    }
    private String setting(Environment env,String name){String value=env.getProperty(name,"");return value.isBlank()?env.getProperty(name.replace("qingfeng.oss.","OSS_").replace('-','_').toUpperCase(java.util.Locale.ROOT),""):value;}
    @Bean @DependsOn("mediaFileStore") com.qingfeng.music.cover.application.CoverFileStore coverFileStore(Environment env)throws IOException{
        Path defaultRoot=Path.of(env.getProperty("qingfeng.media.directory","./data/audio")).resolveSibling("covers");
        return new CoverImageStore(Path.of(env.getProperty("qingfeng.cover.directory",defaultRoot.toString())),client,setting(env,"qingfeng.oss.bucket"),env.getProperty("qingfeng.oss.cover-prefix","qingfeng/covers/"),env.getProperty("qingfeng.media.storage","local").equals("oss"));
    }
    private String required(Environment env,String name){String value=setting(env,name);if(value.isBlank())throw new IllegalArgumentException("缺少存储配置项："+name);return value;}
    @jakarta.annotation.PreDestroy void close(){if(client!=null)client.shutdown();}
}
