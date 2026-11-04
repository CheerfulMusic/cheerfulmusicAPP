package com.qingfeng.music.server.config;

import com.qingfeng.music.generation.application.*;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.infrastructure.persistence.generation.*;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.usage.UsageBudget;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
public class SectionEditingConfiguration {
    @Bean SectionEditRepository sectionEditRepository(JdbcTemplate jdbc){return new JdbcSectionEditRepository(jdbc);}
    @Bean SectionEditingService sectionEditingService(WorkApi works,LyricsApi lyrics,MediaApi media,MusicProvider provider,TaskMapper mapper,SectionEditRepository edits,UsageBudget budget,PlatformTransactionManager manager,Environment env,com.qingfeng.music.lyrics.playback.PlaybackLyricsService playback,com.qingfeng.music.settings.MusicSettings settings){
        var tx=new TransactionTemplate(manager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new SectionEditingService(works,lyrics,media,provider,new MyBatisTaskRepository(mapper),edits,budget,tx,env.getProperty("qingfeng.generation.enabled",Boolean.class,false)&&env.getProperty("qingfeng.generation.section-editing-enabled",Boolean.class,true)).withPlayback(playback).withSettings(settings);
    }
}
