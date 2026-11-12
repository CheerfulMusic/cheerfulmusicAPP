package com.qingfeng.music.server.config;

import com.qingfeng.music.server.agent.*;
import java.time.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
@ConditionalOnProperty(name="qingfeng.agent.chat.enabled",havingValue="true")
public class AgentAdmissionConfiguration {
    @Bean AgentAdmission.Limits agentAdmissionLimits(Environment env){return new AgentAdmission.Limits(
        env.getProperty("qingfeng.agent.admission.global-concurrent",Integer.class,8),env.getProperty("qingfeng.agent.admission.user-concurrent",Integer.class,1),
        env.getProperty("qingfeng.agent.admission.global-rpm",Integer.class,60),env.getProperty("qingfeng.agent.admission.user-rpm",Integer.class,6),
        env.getProperty("qingfeng.agent.admission.global-daily-tokens",Long.class,100_000_000L),env.getProperty("qingfeng.agent.admission.user-daily-tokens",Long.class,2_000_000L),
        env.getProperty("qingfeng.agent.admission.max-prompt-bytes",Integer.class,65536),Duration.ofSeconds(env.getProperty("qingfeng.agent.admission.deadline-seconds",Long.class,150L)+120));}
    @Bean AgentAdmission agentAdmission(JdbcTemplate jdbc,PlatformTransactionManager manager,AgentAdmission.Limits limits,Environment env,ObjectProvider<StringRedisTemplate> redis){
        AgentAdmission database=new JdbcAgentAdmission(jdbc,new TransactionTemplate(manager),limits,ZoneId.of(env.getProperty("qingfeng.usage.zone","Asia/Shanghai")));
        return switch(env.getProperty("qingfeng.agent.admission.mode","db")){
            case "db"->database;
            case "redis"->{if(!env.getProperty("qingfeng.redis.enabled",Boolean.class,false))throw new IllegalArgumentException("Redis Agent admission requires qingfeng.redis.enabled=true");yield new RedisAgentAdmission(database,redis.getObject(),limits,env.getProperty("qingfeng.agent.admission.redis-namespace","qingfeng"));}
            default->throw new IllegalArgumentException("Agent admission mode must be db or redis; unprotected fallback is forbidden");
        };
    }
    @Bean(destroyMethod="close") AgentChatExecutor agentChatExecutor(AgentAdmission admission,AgentChatService chat,AgentAdmission.Limits limits,Environment env){return new AgentChatExecutor(admission,chat,env.getProperty("qingfeng.agent.admission.worker-threads",Integer.class,8),Duration.ofSeconds(env.getProperty("qingfeng.agent.admission.deadline-seconds",Long.class,150L)),limits.maxPromptBytes());}
}
