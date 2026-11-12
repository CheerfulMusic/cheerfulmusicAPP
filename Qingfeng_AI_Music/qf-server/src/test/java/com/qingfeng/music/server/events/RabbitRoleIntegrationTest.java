package com.qingfeng.music.server.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import static org.junit.jupiter.api.Assertions.*;

/** Verify Boot wiring with the isolated broker, not just direct adapter calls. */
@EnabledIfEnvironmentVariable(named="QF_TEST_RABBIT_PORT",matches="15673")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:rabbit_worker_role;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.rabbitmq.host=127.0.0.1","spring.rabbitmq.port=15673","spring.rabbitmq.username=guest","spring.rabbitmq.password=guest","spring.rabbitmq.virtual-host=/","qingfeng.events.dispatcher-enabled=false","qingfeng.payment.inbox.scheduler-enabled=false","qingfeng.cover.ai.scheduler-enabled=false","qingfeng.generation.enabled=false"})
@ActiveProfiles({"test","worker"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RabbitRoleIntegrationTest {
    @Autowired ApplicationContext context;@Autowired RabbitListenerEndpointRegistry listeners;
    @Test void workerHasConfirmedTransportAndOneRunningBoundedConsumerContainer(){
        assertNotNull(context.getBean(DurableTaskDispatcher.Transport.class));
        assertEquals(1,listeners.getListenerContainers().size());
        assertTrue(listeners.getListenerContainers().iterator().next().isRunning());
    }
}
