package com.qingfeng.music.server.config;

import com.qingfeng.music.agent.api.IntentClassifier;
import com.qingfeng.music.agent.application.IntentRouter;
import com.qingfeng.music.conversation.api.ConversationRepository;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.infrastructure.persistence.agent.ConversationMapper;
import com.qingfeng.music.infrastructure.persistence.agent.JdbcConversationRepository;
import com.qingfeng.music.infrastructure.provider.DeepSeekIntentClassifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

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
}
