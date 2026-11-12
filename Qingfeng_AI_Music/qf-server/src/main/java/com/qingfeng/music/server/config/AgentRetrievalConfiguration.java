package com.qingfeng.music.server.config;

import com.qingfeng.music.server.agent.retrieval.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@Profile({"mysql","test"})
public class AgentRetrievalConfiguration {
    @Bean JdbcSemanticIndexQueue semanticIndexQueue(JdbcTemplate jdbc,PlatformTransactionManager tx,Environment env,org.springframework.beans.factory.ObjectProvider<com.qingfeng.music.events.DurableEvents> events){
        return new JdbcSemanticIndexQueue(jdbc,new TransactionTemplate(tx),env.getProperty("qingfeng.agent.retrieval.enabled",Boolean.class,false),events.getIfAvailable(()->(topic,key,payload)->{}));
    }
    @Bean SemanticSource semanticSource(JdbcTemplate jdbc){return new SemanticSource(jdbc);}
    @Bean SemanticGateway semanticGateway(Environment env){
        String p="qingfeng.agent.retrieval.";
        return new HttpSemanticGateway(env.getProperty(p+"enabled",Boolean.class,false),env.getProperty(p+"embedding-url",""),env.getProperty(p+"embedding-model",""),env.getProperty(p+"embedding-api-key",""),env.getProperty(p+"dimensions",Integer.class,0),env.getProperty(p+"qdrant-url",""),env.getProperty(p+"collection",""),env.getProperty(p+"qdrant-api-key",""));
    }
    @Bean SemanticRetrievalActions semanticRetrievalActions(SemanticSource source,SemanticGateway gateway,JdbcSemanticIndexQueue queue){return new SemanticRetrievalActions(source,gateway,queue);}
    @Bean
    @ConditionalOnExpression("${qingfeng.agent.retrieval.enabled:false} and ${qingfeng.agent.retrieval.scheduler-enabled:false}")
    SemanticIndexWorker semanticIndexWorker(JdbcSemanticIndexQueue queue,SemanticSource source,SemanticGateway gateway){return new SemanticIndexWorker(queue,source,gateway);}
}
