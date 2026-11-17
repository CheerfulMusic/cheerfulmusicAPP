package com.qingfeng.music.server.config;

import com.qingfeng.music.events.TaskEventHandler;
import com.qingfeng.music.generation.application.ExecutionRepository.Phase;
import com.qingfeng.music.generation.application.GenerationDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:vocal_worker_startup;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.generation.enabled=true",
    "qingfeng.generation.scheduler-enabled=true",
    "qingfeng.generation.vocal-adjustment-enabled=true",
    "qingfeng.generation.vocal-adjustment-worker-enabled=true"
})
@ActiveProfiles("test")
class VocalAdjustmentWorkerStartupTest {
    @Autowired ApplicationContext application;

    @Test void bothDispatchersStartAlongsideTheMusicReadyHandler() {
        assertEquals("music.ready", application.getBean("musicReadyHandler", TaskEventHandler.class).topic());
        assertTrue(application.getBean("generationRunner", GenerationDispatcher.class).wakeup(Phase.SUBMIT));
        assertTrue(application.getBean("vocalAdjustmentRunner", GenerationDispatcher.class).wakeup(Phase.SUBMIT));
    }
}
