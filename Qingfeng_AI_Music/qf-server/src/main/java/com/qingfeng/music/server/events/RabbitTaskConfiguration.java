package com.qingfeng.music.server.events;

import com.qingfeng.music.events.*;
import com.qingfeng.music.infrastructure.persistence.events.JdbcDurableEvents;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"}) @EnableRabbit
@ConditionalOnProperty(name="qingfeng.events.rabbit.enabled",havingValue="true")
public class RabbitTaskConfiguration {
    public static final String EXCHANGE="qingfeng.tasks.v1", DEAD_EXCHANGE="qingfeng.tasks.dead.v1";
    public static final List<String> TOPICS=List.of("music.ready","payment.inbox","semantic.index");
    @Bean Declarables durableTaskQueues(){
        List<Declarable> declarations=new ArrayList<>();
        declarations.add(new DirectExchange(EXCHANGE,true,false));
        declarations.add(new DirectExchange(DEAD_EXCHANGE,true,false));
        for(String topic:TOPICS){
            String name="qingfeng."+topic+".v1";
            declarations.add(QueueBuilder.durable(name).quorum().withArgument("x-dead-letter-exchange",DEAD_EXCHANGE).withArgument("x-dead-letter-routing-key",topic).build());
            declarations.add(QueueBuilder.durable(name+".dead").quorum().build());
            declarations.add(new Binding(name,Binding.DestinationType.QUEUE,EXCHANGE,topic,null));
            declarations.add(new Binding(name+".dead",Binding.DestinationType.QUEUE,DEAD_EXCHANGE,topic,null));
        }
        return new Declarables(declarations);
    }
    @Bean("durableRabbitTemplate") RabbitTemplate durableRabbitTemplate(ConnectionFactory connection){
        if(!(connection instanceof CachingConnectionFactory cached))throw new IllegalStateException("Durable task publisher requires correlated confirms");
        cached.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        cached.setPublisherReturns(true);
        var template=new RabbitTemplate(connection);template.setMandatory(true);return template;
    }
    @Bean DurableTaskDispatcher.Transport rabbitTaskTransport(@Qualifier("durableRabbitTemplate")RabbitTemplate rabbit){
        return event->{
            if(!TOPICS.contains(event.topic()))throw new IllegalArgumentException("Unknown durable task topic");
            var properties=new MessageProperties();properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);properties.setMessageId(event.id());
            var correlation=new CorrelationData(event.id());
            // Only a database event ID crosses the broker. The worker reloads authoritative data.
            rabbit.send(EXCHANGE,event.topic(),new Message(event.id().getBytes(StandardCharsets.UTF_8),properties),correlation);
            var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
            if(!confirm.isAck()||correlation.getReturned()!=null)throw new IllegalStateException("Task message not durably routed");
        };
    }
    @Bean("durableTaskListener") SimpleRabbitListenerContainerFactory durableTaskListener(ConnectionFactory connection,Environment env){
        var factory=new SimpleRabbitListenerContainerFactory();factory.setConnectionFactory(connection);
        int workers=env.getProperty("qingfeng.events.rabbit.consumers",Integer.class,2);
        if(workers<1||workers>16)throw new IllegalArgumentException("Task consumers must be between 1 and 16");
        factory.setConcurrentConsumers(workers);factory.setMaxConcurrentConsumers(workers);factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);factory.setDefaultRequeueRejected(false);
        return factory;
    }
    @Bean @ConditionalOnProperty(name="qingfeng.events.rabbit.consumer-enabled",havingValue="true",matchIfMissing=true)
    RabbitTaskConsumer rabbitTaskConsumer(JdbcDurableEvents outbox,ObjectProvider<TaskEventHandler> handlers){return new RabbitTaskConsumer(outbox,handlers.orderedStream().toList());}
    public static final class RabbitTaskConsumer {
        private final JdbcDurableEvents outbox;private final DurableTaskDispatcher.Transport local;
        RabbitTaskConsumer(JdbcDurableEvents outbox,List<TaskEventHandler> handlers){this.outbox=outbox;local=DurableTaskDispatcher.local(handlers);}
        @RabbitListener(queues={"qingfeng.music.ready.v1","qingfeng.payment.inbox.v1","qingfeng.semantic.index.v1"},containerFactory="durableTaskListener")
        public void receive(Message message)throws Exception{
            if(message.getBody().length!=36)throw new IllegalArgumentException("Invalid task event ID");
            var event=outbox.find(new String(message.getBody(),StandardCharsets.UTF_8)).orElseThrow(()->new IllegalArgumentException("Unknown task event"));
            if(!event.topic().equals(message.getMessageProperties().getReceivedRoutingKey()))throw new IllegalArgumentException("Task topic mismatch");
            local.send(event);
        }
    }
}
