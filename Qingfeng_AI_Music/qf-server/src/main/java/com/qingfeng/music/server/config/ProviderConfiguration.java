package com.qingfeng.music.server.config;

import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.infrastructure.provider.UnavailableMusicProvider;
import com.qingfeng.music.infrastructure.provider.KieSunoMusicProvider;
import com.qingfeng.music.infrastructure.provider.KieRequestLimiter;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.qingfeng.music.infrastructure.storage.ProviderAudioUrls;

@Configuration(proxyBeanMethods = false)
public class ProviderConfiguration {
    @Bean ProviderAudioUrls providerAudioUrls() {return new ProviderAudioUrls();}
    @Bean @org.springframework.context.annotation.Profile({"mysql","test"})
    KieRequestLimiter kieRequestLimiter(org.springframework.jdbc.core.JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager manager,Environment env){
        return new KieRequestLimiter(jdbc,new org.springframework.transaction.support.TransactionTemplate(manager),
            env.getProperty("qingfeng.providers.kie.rate-limit.scope","primary"),
            env.getProperty("qingfeng.providers.kie.rate-limit.creates-per-ten-seconds",Integer.class,18),
            env.getProperty("qingfeng.providers.kie.rate-limit.reads-per-second",Integer.class,8),
            java.time.Duration.ofSeconds(env.getProperty("qingfeng.providers.kie.rate-limit.max-wait-seconds",Long.class,30L)));
    }
    @Bean MusicProvider musicProvider(Environment environment,ProviderAudioUrls audioUrls,
            org.springframework.beans.factory.ObjectProvider<KieRequestLimiter> limiters) {
        if (!"kie-suno".equals(environment.getProperty("qingfeng.generation.provider","unconfigured"))) return new UnavailableMusicProvider();
        String key=environment.getProperty("qingfeng.providers.kie.api-key",environment.getProperty("KIE_API_KEY",""));
        var tuning=new KieSunoMusicProvider.Tuning(
            environment.getProperty("qingfeng.providers.kie.style-weight",Double.class,0.85),
            environment.getProperty("qingfeng.providers.kie.weirdness-constraint",Double.class,0.25),
            environment.getProperty("qingfeng.providers.kie.variety",Integer.class,0));
        var provider=new KieSunoMusicProvider(key,environment.getProperty("qingfeng.providers.kie.model","V6"),tuning).sectionAudioWeight(environment.getProperty("qingfeng.providers.kie.section-audio-weight",Double.class,0.85)).approveAudioWith(audioUrls::approve)
            .withLimits(new com.qingfeng.music.settings.MusicPromptLimits(
                environment.getProperty("qingfeng.providers.kie.max-title-characters",Integer.class,80),
                environment.getProperty("qingfeng.providers.kie.max-lyrics-characters",Integer.class,5000),
                environment.getProperty("qingfeng.providers.kie.max-style-characters",Integer.class,1000),
                environment.getProperty("qingfeng.providers.kie.max-negative-characters",Integer.class,500)));
        var limiter=limiters.getIfAvailable();
        if(limiter==null)throw new IllegalStateException("KIE音乐生成必须配置共享请求限流");
        provider.withRequestLimits(limiter::beforeCreate,limiter::beforeRead);
        return new com.qingfeng.music.generation.application.ResilientMusicProvider(provider,java.time.Clock.systemUTC());
    }
}
