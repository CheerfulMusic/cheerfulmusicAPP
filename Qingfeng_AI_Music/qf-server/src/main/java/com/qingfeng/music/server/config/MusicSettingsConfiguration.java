package com.qingfeng.music.server.config;
import com.qingfeng.music.settings.*;
import com.qingfeng.music.infrastructure.persistence.generation.JdbcMusicSettingsRepository;
import com.qingfeng.music.work.api.WorkApi;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
public class MusicSettingsConfiguration {
 @Bean MusicSettings musicSettings(JdbcTemplate jdbc,WorkApi works)throws java.io.IOException{try(var in=new ClassPathResource("music/catalog-v1.json").getInputStream()){return new MusicSettingsService(new ObjectMapper().readValue(in,MusicSettings.Catalog.class),new JdbcMusicSettingsRepository(jdbc),works);}}
}
