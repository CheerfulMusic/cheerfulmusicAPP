package com.qingfeng.music.server.config;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.agent.application.AgentToolDispatcher;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.infrastructure.persistence.agent.JdbcAgentTurnStore;
import com.qingfeng.music.infrastructure.provider.DeepSeekAgentChatModel;
import com.qingfeng.music.server.agent.AgentChatService;
import com.qingfeng.music.server.agent.AgentLibraryActions;
import com.qingfeng.music.server.agent.AgentStudioActions;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.cover.api.CoverApi;
import com.qingfeng.music.lyrics.application.InspirationRewriteService;
import com.qingfeng.music.lyrics.application.LyricLineRewriteService;
import com.qingfeng.music.lyrics.playback.PlaybackLyricsService;
import com.qingfeng.music.generation.application.SectionEditingService;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.settings.MusicBriefService;
import com.qingfeng.music.server.hotsong.HotSongService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@Profile({"mysql","test"})
@ConditionalOnProperty(name="qingfeng.agent.chat.enabled",havingValue="true")
public class AgentConversationChatConfiguration {
    @Bean AgentChatModel agentChatModel(Environment env){
        return new DeepSeekAgentChatModel(env.getProperty("qingfeng.providers.deepseek.api-key",env.getProperty("DEEPSEEK_API_KEY","")),
                env.getProperty("qingfeng.providers.deepseek.model","deepseek-flash"),env.getProperty("qingfeng.agent.intent.llm-enabled",Boolean.class,false));
    }
    @Bean AgentTurnStore agentTurnStore(JdbcTemplate jdbc,PlatformTransactionManager manager){return new JdbcAgentTurnStore(jdbc,new TransactionTemplate(manager));}
    @Bean AgentLibraryActions agentLibraryActions(WorkApi works,LyricsApi lyrics,MediaApi media,GenerationApi generation,CoverApi covers,
            ConversationService conversations,HotSongService hot,InspirationRewriteService inspiration,com.qingfeng.music.server.query.WorkLibraryQuery library){
        return new AgentLibraryActions(works,lyrics,media,generation,covers,conversations,hot,inspiration).withLibrary(library);
    }
    @Bean AgentStudioActions agentStudioActions(WorkApi works,MusicSettings settings,MusicBriefService briefs,LyricLineRewriteService lines,
            PlaybackLyricsService playback,SectionEditingService sections,ConversationService conversations){
        return new AgentStudioActions(works,settings,briefs,lines,playback,sections,conversations);
    }
    @Bean AgentChatService agentChatService(ConversationService conversations,AgentToolDispatcher tools,AgentChatModel model,AgentTurnStore turns,
            ObjectProvider<AgentSocialActions> social,ObjectProvider<AgentApplicationActions> applications){
        return new AgentChatService(conversations,tools,model,turns,social.getIfAvailable(),applications.orderedStream().toList());
    }
}
