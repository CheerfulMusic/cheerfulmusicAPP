package com.qingfeng.music.server.config;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.agent.application.AgentToolDispatcher;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.infrastructure.persistence.agent.JdbcAgentTurnStore;
import com.qingfeng.music.infrastructure.provider.QwenAgentChatModel;
import com.qingfeng.music.infrastructure.provider.ResilientAgentChatModel;
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
        var delegate=new QwenAgentChatModel(RewriteProviderSettings.key(env), RewriteProviderSettings.model(env),
                env.getProperty("qingfeng.agent.intent.llm-enabled",Boolean.class,false),
                java.net.URI.create(RewriteProviderSettings.endpoint(env)));
        return new ResilientAgentChatModel(delegate,
                env.getProperty("qingfeng.agent.intent.circuit.failure-threshold",Integer.class,3),
                java.time.Duration.ofSeconds(env.getProperty("qingfeng.agent.intent.circuit.cooldown-seconds",Long.class,30L)));
    }
    @Bean AgentTurnStore agentTurnStore(JdbcTemplate jdbc,PlatformTransactionManager manager){return new JdbcAgentTurnStore(jdbc,new TransactionTemplate(manager));}
    @Bean AgentLibraryActions agentLibraryActions(WorkApi works,LyricsApi lyrics,MediaApi media,GenerationApi generation,CoverApi covers,
            ConversationService conversations,HotSongService hot,InspirationRewriteService inspiration,com.qingfeng.music.server.query.WorkLibraryQuery library,PlatformTransactionManager manager){
        return new AgentLibraryActions(works,lyrics,media,generation,covers,conversations,hot,inspiration,new TransactionTemplate(manager)).withLibrary(library);
    }
    @Bean AgentStudioActions agentStudioActions(WorkApi works,MusicSettings settings,MusicBriefService briefs,LyricLineRewriteService lines,
            PlaybackLyricsService playback,SectionEditingService sections,ConversationService conversations){
        return new AgentStudioActions(works,settings,briefs,lines,playback,sections,conversations);
    }
    @Bean com.qingfeng.music.server.agent.AgentFeatureActions agentFeatureActions(WorkApi works,LyricsApi lyrics,ConversationService conversations,
            com.qingfeng.music.server.artist.ArtistProfileService profiles,ObjectProvider<com.qingfeng.music.creation.QuickCreationService> quick,
            com.qingfeng.music.lyrics.application.LyricSelectionRewriteService selection,LyricLineRewriteService lines,
            com.qingfeng.music.lyrics.application.LyricsCompositionService composer,MusicSettings settings,
            com.qingfeng.music.server.download.SharedDownloadGrants downloads,com.qingfeng.music.credits.CreditsPricing pricing){
        return new com.qingfeng.music.server.agent.AgentFeatureActions(works,lyrics,conversations,profiles,quick,selection,lines,composer,settings,downloads,pricing);
    }
    @Bean AgentChatService agentChatService(ConversationService conversations,AgentToolDispatcher tools,AgentChatModel model,AgentTurnStore turns,
            ObjectProvider<AgentSocialActions> social,ObjectProvider<AgentApplicationActions> applications,Environment env,
            com.qingfeng.music.credits.CreditsPricing pricing,com.qingfeng.music.credits.LyricFreeAllowance allowance){
        return new AgentChatService(conversations,tools,model,turns,social.getIfAvailable(),applications.orderedStream().toList())
                .withCreationPricing(pricing,allowance).withProgress(env.getProperty("qingfeng.agent.progress.enabled",Boolean.class,true));
    }
}
