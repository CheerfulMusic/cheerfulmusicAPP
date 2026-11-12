package com.qingfeng.music.server.events;

import com.qingfeng.music.events.*;
import com.qingfeng.music.generation.application.GenerationDispatcher;
import com.qingfeng.music.generation.application.ExecutionRepository;
import com.qingfeng.music.server.payment.VerifiedPaymentProcessor;
import com.qingfeng.music.server.agent.retrieval.SemanticIndexWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
public class BusinessTaskHandlers {
    @Bean @ConditionalOnProperty(name={"qingfeng.generation.enabled","qingfeng.generation.scheduler-enabled"},havingValue="true")
    TaskEventHandler musicReadyHandler(GenerationDispatcher dispatcher){return handler("music.ready",()->dispatcher.wakeup(ExecutionRepository.Phase.SUBMIT));}
    @Bean @ConditionalOnProperty(name="qingfeng.payment.inbox.scheduler-enabled",havingValue="true",matchIfMissing=true)
    TaskEventHandler paymentInboxHandler(java.util.List<VerifiedPaymentProcessor> payments){return handler("payment.inbox",()->payments.forEach(VerifiedPaymentProcessor::processNextEvent));}
    @Bean @ConditionalOnExpression("${qingfeng.agent.retrieval.enabled:false} and ${qingfeng.agent.retrieval.scheduler-enabled:false}")
    TaskEventHandler semanticIndexHandler(SemanticIndexWorker worker){return handler("semantic.index",worker::processOnce);}
    public static TaskEventHandler handler(String topic,Runnable work){return new TaskEventHandler(){public String topic(){return topic;}public void handle(DurableEvents.Event event){work.run();}};}
}
