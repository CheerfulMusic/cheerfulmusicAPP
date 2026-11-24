package com.qingfeng.music.server.config;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.qingfeng.music.media.application.MediaFileStore;
import com.qingfeng.music.infrastructure.storage.*;
import com.qingfeng.music.server.hotsong.HotSongCreationReferences;
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
            String archiveFormat = env.getProperty("qingfeng.oss.audio-format", "original");
            if (!archiveFormat.equals("original") && !archiveFormat.equals("wav")) {
                throw new IllegalArgumentException("OSS 音频归档格式必须为 original 或 wav");
            }
            String encoder = archiveFormat.equals("wav") ? env.getProperty("qingfeng.oss.audio-ffmpeg",
                    env.getProperty("qingfeng.video.ffmpeg", "ffmpeg")) : null;
            var staging = new LocalMediaFileStore(
                    Path.of(env.getProperty("qingfeng.media.staging-directory", "./data/staging")), encoder);
            oss = new OssMediaFileStore(client, required(env,"qingfeng.oss.bucket"),
                    env.getProperty("qingfeng.oss.prefix","qingfeng/audio/"), staging);
        }
        return new RoutingMediaFileStore(local,oss,mode.equals("oss"));
    }
    private String setting(Environment env,String name){String value=env.getProperty(name,"");return value.isBlank()?env.getProperty(name.replace("qingfeng.oss.","OSS_").replace('-','_').toUpperCase(java.util.Locale.ROOT),""):value;}
    @Bean @DependsOn("mediaFileStore") com.qingfeng.music.cover.application.CoverFileStore coverFileStore(Environment env)throws IOException{
        Path defaultRoot=Path.of(env.getProperty("qingfeng.media.directory","./data/audio")).resolveSibling("covers");
        return new CoverImageStore(Path.of(env.getProperty("qingfeng.cover.directory",defaultRoot.toString())),client,setting(env,"qingfeng.oss.bucket"),env.getProperty("qingfeng.oss.cover-prefix","qingfeng/covers/"),env.getProperty("qingfeng.media.storage","local").equals("oss"));
    }
    @Bean @DependsOn("mediaFileStore")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="qingfeng.ai.capacity.enabled",havingValue="true")
    com.qingfeng.music.server.ai.AiPhotoInputs aiPhotoInputs(Environment env) {
        return new com.qingfeng.music.server.ai.AiPhotoInputs(client, setting(env,"qingfeng.oss.bucket"),
            Path.of(env.getProperty("qingfeng.ai.capacity.photo-directory", "./data/ai-inputs")));
    }
    @Bean @DependsOn("mediaFileStore") com.qingfeng.music.server.web.RecommendationOssRedirect recommendationOssRedirect(Environment env,com.qingfeng.music.server.web.MediaCdnSigner cdn){
        return new com.qingfeng.music.server.web.RecommendationOssRedirect(client,setting(env,"qingfeng.oss.bucket"),env.getProperty("qingfeng.recommendation-oss.enabled",Boolean.class,false),java.time.Clock.systemUTC(),cdn);
    }
    @Bean @DependsOn("mediaFileStore")
    com.qingfeng.music.generation.application.AudioReferenceClips audioReferenceClips(Environment env,org.springframework.jdbc.core.JdbcTemplate db){
        var approved=new java.util.LinkedHashMap<String,com.qingfeng.music.generation.application.AudioReferenceClips.Clip>();
        if(env.getProperty("qingfeng.generation.audio-reference.enabled",Boolean.class,false)){
            double weight=env.getProperty("qingfeng.generation.audio-reference.weight",Double.class,0.3);
            if(!Double.isFinite(weight)||weight<0||weight>1)throw new IllegalArgumentException("参考音频权重必须在 0 到 1 之间");
            for(String id:HotSongCreationReferences.trackIds()){
                String key=env.getProperty("qingfeng.generation.audio-reference.clips."+id,"").trim();
                if(!key.isEmpty())approved.put(id,new com.qingfeng.music.generation.application.AudioReferenceClips.Clip(key,Math.round(weight*100)/100.0));
            }
        }
        return new com.qingfeng.music.server.hotsong.OssAudioReferenceClips(db,approved,client,setting(env,"qingfeng.oss.bucket"),java.time.Clock.systemUTC());
    }
    private String required(Environment env,String name){String value=setting(env,name);if(value.isBlank())throw new IllegalArgumentException("缺少存储配置项："+name);return value;}
    @jakarta.annotation.PreDestroy void close(){if(client!=null)client.shutdown();}
}
