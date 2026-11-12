package com.qingfeng.music.server.config;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.agent.application.AgentToolDispatcher;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.infrastructure.persistence.agent.JdbcAgentTurnStore;
import com.qingfeng.music.infrastructure.provider.DeepSeekAgentChatModel;
import com.qingfeng.music.server.agent.AgentChatService;
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
    @Bean AgentChatService agentChatService(ConversationService conversations,AgentToolDispatcher tools,AgentChatModel model,AgentTurnStore turns,ObjectProvider<AgentSocialActions> social){
        return new AgentChatService(conversations,tools,model,turns,social.getIfAvailable());
    }
}
