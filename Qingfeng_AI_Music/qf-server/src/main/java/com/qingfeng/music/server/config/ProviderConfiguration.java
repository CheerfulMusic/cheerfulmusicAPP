package com.qingfeng.music.server.config;

import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.infrastructure.provider.UnavailableMusicProvider;
import com.qingfeng.music.infrastructure.provider.KieSunoMusicProvider;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.qingfeng.music.infrastructure.storage.ProviderAudioUrls;

@Configuration(proxyBeanMethods = false)
public class ProviderConfiguration {
    @Bean ProviderAudioUrls providerAudioUrls() {return new ProviderAudioUrls();}
    @Bean MusicProvider musicProvider(Environment environment,ProviderAudioUrls audioUrls) {
        if (!"kie-suno".equals(environment.getProperty("qingfeng.generation.provider","unconfigured"))) return new UnavailableMusicProvider();
        String key=environment.getProperty("qingfeng.providers.kie.api-key",environment.getProperty("KIE_API_KEY",""));
        return new KieSunoMusicProvider(key,environment.getProperty("qingfeng.providers.kie.model","V6")).approveAudioWith(audioUrls::approve);
    }
}
