package com.qingfeng.music.server.events;

import com.qingfeng.music.events.*;
import com.qingfeng.music.infrastructure.persistence.events.JdbcDurableEvents;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"}) @EnableScheduling
public class DurableTaskConfiguration {
    @Bean JdbcDurableEvents durableEvents(JdbcTemplate db,PlatformTransactionManager manager){return new JdbcDurableEvents(db,new TransactionTemplate(manager));}
    @Bean @ConditionalOnProperty(name="qingfeng.events.dispatcher-enabled",havingValue="true",matchIfMissing=true)
    DurableTaskDispatcher durableTaskDispatcher(JdbcDurableEvents outbox,ObjectProvider<DurableTaskDispatcher.Transport> broker,ObjectProvider<TaskEventHandler> handlers,@Value("${qingfeng.events.batch-size:32}")int batchSize){
        return new DurableTaskDispatcher(outbox,broker.getIfAvailable(()->DurableTaskDispatcher.local(handlers.orderedStream().toList())),batchSize);
    }
    @Bean io.micrometer.core.instrument.binder.MeterBinder durableTaskMetrics(JdbcDurableEvents outbox){
        return registry->{for(String state:java.util.List.of("READY","SENDING","SENT","DEAD"))
            io.micrometer.core.instrument.Gauge.builder("qingfeng.task.outbox",outbox,value->value.count(state)).tag("state",state).register(registry);};
    }
}
