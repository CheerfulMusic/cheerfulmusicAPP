package com.qingfeng.music.server.avatar;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
public class AvatarGenerationConfiguration {
    @Bean AvatarImageProvider avatarImageProvider(Environment env,com.qingfeng.music.infrastructure.provider.KieRequestLimiter limiter) {
        String key=env.getProperty("qingfeng.providers.kie.image-api-key",env.getProperty("qingfeng.providers.kie.api-key",env.getProperty("KIE_API_KEY","")));
        boolean enabled=env.getProperty("qingfeng.avatar.ai.enabled",Boolean.class,false) && env.getProperty("qingfeng.avatar.ai.scheduler-enabled",Boolean.class,false);
        return new KieAvatarImageProvider(key,enabled,limiter::beforeCreate,limiter::beforeRead);
    }
    @Bean AvatarGenerationService avatarGenerationService(JdbcTemplate db,PlatformTransactionManager manager,AvatarService avatars,AvatarImageProvider provider,com.qingfeng.music.credits.CreditsLedger credits,com.qingfeng.music.credits.CreditsPricing pricing,Environment env) {
        return new AvatarGenerationService(db,new TransactionTemplate(manager),avatars,provider,credits,pricing,env.getProperty("qingfeng.avatar.ai.daily-limit",Integer.class,3));
    }
    @Bean AgentAvatarActions agentAvatarActions(AvatarGenerationService service) { return new AgentAvatarActions(service); }
    @Bean AvatarGenerationRunner avatarGenerationRunner(AvatarGenerationService service,Environment env) { return new AvatarGenerationRunner(service,env.getProperty("qingfeng.avatar.ai.scheduler-enabled",Boolean.class,false)); }
    static final class AvatarGenerationRunner {
        private final AvatarGenerationService service; private final boolean enabled;
        AvatarGenerationRunner(AvatarGenerationService service,boolean enabled){this.service=service;this.enabled=enabled;}
        @Scheduled(fixedDelayString="${qingfeng.avatar.ai.scan-ms:5000}") public void run(){if(enabled)try{service.runOnce();}catch(RuntimeException ignored){ /* Retry on next scan. */ }}
    }
}
