package com.qingfeng.music.server.config;

import com.qingfeng.music.agent.api.IntentClassifier;
import com.qingfeng.music.agent.application.AgentToolDispatcher;
import com.qingfeng.music.agent.application.AgentToolHandler;
import com.qingfeng.music.agent.application.AgentToolRegistry;
import com.qingfeng.music.agent.application.PendingIntentDispatcher;
import com.qingfeng.music.agent.application.handler.ExtendSongHandler;
import com.qingfeng.music.agent.application.handler.GenerateLyricsHandler;
import com.qingfeng.music.agent.application.handler.GenerateSongHandler;
import com.qingfeng.music.agent.application.handler.GetCurrentSongHandler;
import com.qingfeng.music.agent.application.handler.ModifySectionHandler;
import com.qingfeng.music.agent.application.handler.SetStyleHandler;
import com.qingfeng.music.agent.application.IntentRouter;
import com.qingfeng.music.conversation.api.ConversationRepository;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.SectionEditingService;
import com.qingfeng.music.infrastructure.persistence.agent.ConversationMapper;
import com.qingfeng.music.infrastructure.persistence.agent.JdbcConversationRepository;
import com.qingfeng.music.infrastructure.provider.DeepSeekIntentClassifier;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * W1 装配。所有开关走代码默认值，不改 application.yml：
 * - qingfeng.agent.chat.enabled 只控制 AgentConversationController 是否注册（默认 false → 404）
 * - qingfeng.agent.intent.llm-enabled 默认 false，不产生真实模型请求
 * - qingfeng.agent.intent.confidence-threshold 默认 0.7
 */
@Configuration(proxyBeanMethods = false)
@Profile({"mysql", "test"})
public class AgentChatConfiguration {

    @Bean
    JdbcConversationRepository agentConversationRepository(ConversationMapper mapper) {
        return new JdbcConversationRepository(mapper);
    }

    @Bean
    ConversationService conversationService(ConversationRepository repository) {
        return new ConversationService(repository);
    }

    @Bean
    IntentClassifier intentClassifier(Environment env) {
        return new DeepSeekIntentClassifier(
                env.getProperty("qingfeng.providers.deepseek.api-key", env.getProperty("DEEPSEEK_API_KEY", "")),
                env.getProperty("qingfeng.providers.deepseek.model", "deepseek-flash"));
    }

    @Bean
    IntentRouter intentRouter(IntentClassifier classifier, Environment env) {
        return new IntentRouter(classifier,
                env.getProperty("qingfeng.agent.intent.llm-enabled", Boolean.class, false),
                env.getProperty("qingfeng.agent.intent.confidence-threshold", Double.class, 0.7));
    }

    // ===== W2：工具注册 / 分发 / 6 个处理器 / 排队 =====

    @Bean
    AgentToolRegistry agentToolRegistry() {
        return new AgentToolRegistry();
    }

    @Bean
    GetCurrentSongHandler getCurrentSongHandler(WorkApi works, LyricsApi lyrics, MediaApi media, GenerationApi generation) {
        return new GetCurrentSongHandler(works, lyrics, media, generation);
    }

    @Bean
    SetStyleHandler setStyleHandler(WorkApi works, LyricsApi lyrics, MediaApi media, com.qingfeng.music.settings.MusicSettings settings) {
        return new SetStyleHandler(works, lyrics, media, settings);
    }

    @Bean
    GenerateSongHandler generateSongHandler(WorkApi works, LyricsApi lyrics,
                                            LyricsCompositionService composer, GenerationApi generation,
                                            MediaApi media, com.qingfeng.music.settings.MusicSettings settings) {
        return new GenerateSongHandler(works, lyrics, composer, generation, media, settings);
    }

    @Bean
    GenerateLyricsHandler generateLyricsHandler(WorkApi works, LyricsApi lyrics,
                                                LyricsCompositionService composer, MediaApi media, com.qingfeng.music.settings.MusicSettings settings) {
        return new GenerateLyricsHandler(works, lyrics, composer, media, settings);
    }

    @Bean
    ModifySectionHandler modifySectionHandler(WorkApi works, LyricsApi lyrics, MediaApi media,
                                             SectionEditingService editing) {
        return new ModifySectionHandler(works, lyrics, media, editing);
    }

    @Bean
    ExtendSongHandler extendSongHandler(WorkApi works, LyricsApi lyrics,
                                       LyricsCompositionService composer, GenerationApi generation,
                                       MediaApi media) {
        return new ExtendSongHandler(works, lyrics, composer, generation, media);
    }

    @Bean
    AgentToolDispatcher agentToolDispatcher(AgentToolRegistry registry, ConversationService conversations,
                                           GenerationApi generation, GetCurrentSongHandler getCurrentSong,
                                           SetStyleHandler setStyle, GenerateSongHandler generateSong,
                                           GenerateLyricsHandler generateLyrics,
                                           ModifySectionHandler modifySection, ExtendSongHandler extendSong) {
        Map<com.qingfeng.music.agent.api.AgentTool, AgentToolHandler> handlers = new LinkedHashMap<>();
        handlers.put(com.qingfeng.music.agent.api.AgentTool.GET_CURRENT_SONG, getCurrentSong);
        handlers.put(com.qingfeng.music.agent.api.AgentTool.SET_STYLE, setStyle);
        handlers.put(com.qingfeng.music.agent.api.AgentTool.GENERATE_SONG, generateSong);
        handlers.put(com.qingfeng.music.agent.api.AgentTool.GENERATE_LYRICS, generateLyrics);
        handlers.put(com.qingfeng.music.agent.api.AgentTool.MODIFY_SECTION, modifySection);
        handlers.put(com.qingfeng.music.agent.api.AgentTool.EXTEND_SONG, extendSong);
        return new AgentToolDispatcher(registry, handlers, conversations, generation);
    }

    @Bean
    PendingIntentDispatcher pendingIntentDispatcher(ConversationRepository repository,
                                                   AgentToolDispatcher dispatcher, GenerationApi generation) {
        return new PendingIntentDispatcher(repository, dispatcher, generation);
    }

    /** 每 3 秒扫描排队意图；仅 agent.chat 启用时注册。复用 GenerationWorker 同款轮询节奏，不引入消息队列。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "qingfeng.agent.legacy-pending.enabled", havingValue = "true")
    PendingIntentScheduler pendingIntentScheduler(PendingIntentDispatcher dispatcher) {
        return new PendingIntentScheduler(dispatcher);
    }
}
