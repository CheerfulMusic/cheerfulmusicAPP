package com.qingfeng.music.server.config;

import com.qingfeng.music.agent.api.ReferenceAnalyst;
import com.qingfeng.music.agent.application.ReferenceAnalysisService;
import com.qingfeng.music.infrastructure.provider.DeepSeekReferenceAnalyst;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 参考音乐语义化装配：复用 qingfeng.providers.deepseek.*，不新增第二套密钥配置。 */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {
    @Bean
    ReferenceAnalyst referenceAnalyst(Environment env) {
        return new DeepSeekReferenceAnalyst(
            env.getProperty("qingfeng.providers.deepseek.api-key", env.getProperty("DEEPSEEK_API_KEY", "")),
            env.getProperty("qingfeng.providers.deepseek.model", "deepseek-flash"));
    }

    @Bean
    ReferenceAnalysisService referenceAnalysisService(ReferenceAnalyst analyst, Environment env) {
        return new ReferenceAnalysisService(analyst,
            env.getProperty("qingfeng.reference.analysis.enabled", Boolean.class, true),
            env.getProperty("qingfeng.reference.analysis.concurrent-global", Integer.class, 2));
    }
}
