package com.qingfeng.music.server.events;

import com.qingfeng.music.infrastructure.persistence.events.JdbcDurableEvents;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Only the explicitly isolated local test broker; never reads application Rabbit settings. */
@EnabledIfEnvironmentVariable(named="QF_TEST_RABBIT_PORT",matches="15673")
class RabbitTaskIntegrationTest {
    CachingConnectionFactory connection;RabbitTemplate rabbit;RabbitAdmin admin;JdbcDurableEvents outbox;JdbcTemplate db;
    RabbitTaskConfiguration config=new RabbitTaskConfiguration();
    static final String QUEUE="qingfeng.music.ready.v1";
    @BeforeEach void setup(){
        connection=new CachingConnectionFactory("127.0.0.1",15673);connection.setUsername("guest");connection.setPassword("guest");
        rabbit=config.durableRabbitTemplate(connection);admin=new RabbitAdmin(connection);
        var declarations=config.durableTaskQueues();
        for(var exchange:declarations.getDeclarablesByType(Exchange.class))admin.declareExchange(exchange);
        for(var queue:declarations.getDeclarablesByType(org.springframework.amqp.core.Queue.class))admin.declareQueue(queue);
        for(var binding:declarations.getDeclarablesByType(Binding.class))admin.declareBinding(binding);
        admin.purgeQueue(QUEUE,true);
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V37__durable_task_outbox.sql")).execute(ds);
        db=new JdbcTemplate(ds);outbox=new JdbcDurableEvents(db,new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }
    @AfterEach void close(){if(connection!=null)connection.destroy();}
    @Test void confirmsPersistentIdOnlyMessageAndConsumerReloadsAuthoritativeEvent()throws Exception{
        outbox.publish("music.ready","work-1","private-work-id");
        new DurableTaskDispatcher(outbox,config.rabbitTaskTransport(rabbit)).poll();
        assertEquals(1,outbox.count("SENT"));
        var message=rabbit.receive(QUEUE,5000);assertNotNull(message);
        assertEquals(36,message.getBody().length);assertEquals(MessageDeliveryMode.PERSISTENT,message.getMessageProperties().getReceivedDeliveryMode());
        var calls=new AtomicInteger();
        var consumer=new RabbitTaskConfiguration.RabbitTaskConsumer(outbox,List.of(BusinessTaskHandlers.handler("music.ready",calls::incrementAndGet)));
        consumer.receive(message);consumer.receive(message);assertEquals(2,calls.get()); // wakeups are at-least-once; business workers own fencing.
        message.getMessageProperties().setReceivedRoutingKey("payment.inbox");
        assertThrows(IllegalArgumentException.class,()->consumer.receive(message));assertEquals(2,calls.get());
    }
    @Test void unroutableMessageRemainsInDatabaseUntilBindingRecovers(){
        var binding=new Binding(QUEUE,Binding.DestinationType.QUEUE,RabbitTaskConfiguration.EXCHANGE,"music.ready",null);
        admin.removeBinding(binding);
        try{
            outbox.publish("music.ready","work-2","work-2");
            new DurableTaskDispatcher(outbox,config.rabbitTaskTransport(rabbit)).poll();
            assertEquals(1,outbox.count("READY"));assertEquals(0,outbox.count("SENT"));
        }finally{admin.declareBinding(binding);}
        db.update("UPDATE task_event_outbox SET next_attempt_at=CURRENT_TIMESTAMP");
        new DurableTaskDispatcher(outbox,config.rabbitTaskTransport(rabbit)).poll();assertEquals(1,outbox.count("SENT"));
        assertNotNull(rabbit.receive(QUEUE,5000));
    }
}
