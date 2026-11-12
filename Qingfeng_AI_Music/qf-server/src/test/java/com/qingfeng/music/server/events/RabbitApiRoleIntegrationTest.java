package com.qingfeng.music.server.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import com.qingfeng.music.generation.application.GenerationDispatcher;
import com.qingfeng.music.server.agent.retrieval.SemanticIndexWorker;
import com.qingfeng.music.server.payment.PaymentInboxRunner;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QF_TEST_RABBIT_PORT",matches="15673")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:rabbit_api_role;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.rabbitmq.host=127.0.0.1","spring.rabbitmq.port=15673","spring.rabbitmq.username=guest","spring.rabbitmq.password=guest","spring.rabbitmq.virtual-host=/","qingfeng.events.dispatcher-enabled=false"})
@ActiveProfiles({"test","api"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RabbitApiRoleIntegrationTest {
    @Autowired ApplicationContext context;@Autowired RabbitListenerEndpointRegistry listeners;
    @Test void apiCanPublishButDoesNotRunConsumersOrBusinessSchedulers(){
        assertNotNull(context.getBean(DurableTaskDispatcher.Transport.class));assertTrue(listeners.getListenerContainers().isEmpty());
        assertTrue(context.getBeansOfType(GenerationDispatcher.class).isEmpty());
        assertTrue(context.getBeansOfType(PaymentInboxRunner.class).isEmpty());
        assertTrue(context.getBeansOfType(SemanticIndexWorker.class).isEmpty());
        assertTrue(context.getEnvironment().getProperty("qingfeng.quick.accept-enabled",Boolean.class,false));
    }
}
