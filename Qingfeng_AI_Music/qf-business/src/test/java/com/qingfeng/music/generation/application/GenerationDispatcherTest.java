package com.qingfeng.music.generation.application;

import java.time.Duration;
import java.util.EnumMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationDispatcherTest {
    @Test void blockingStagesRunIndependentlyAndStayWithinTheirWorkerBudgets() throws Exception {
        var calls = new EnumMap<ExecutionRepository.Phase, AtomicInteger>(ExecutionRepository.Phase.class);
        for (var phase : ExecutionRepository.Phase.values()) calls.put(phase, new AtomicInteger());
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        try (var dispatcher = new GenerationDispatcher(phase -> {
            calls.get(phase).incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放阶段");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, Duration.ofMillis(10), 1, 2, 1)) {
            try {
                dispatcher.start();dispatcher.start();
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(1, calls.get(ExecutionRepository.Phase.SUBMIT).get());
                assertEquals(2, calls.get(ExecutionRepository.Phase.QUERY).get());
                assertEquals(1, calls.get(ExecutionRepository.Phase.ARCHIVE).get());
                assertEquals(0, calls.get(ExecutionRepository.Phase.ANY).get());
            } finally { release.countDown(); }
        }
    }

    @Test void oneFailedScanDoesNotKillSubsequentScans() throws Exception {
        var attempts = new AtomicInteger();
        var resumed = new CountDownLatch(1);
        try (var dispatcher = new GenerationDispatcher(phase -> {
            if (phase == ExecutionRepository.Phase.SUBMIT) {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("模拟数据库短暂异常");
                resumed.countDown();
            }
        }, Duration.ofMillis(10), 1, 1, 1)) {
            dispatcher.start();
            assertTrue(resumed.await(3, TimeUnit.SECONDS));
        }
    }

    @Test void invalidBudgetsFailBeforeStartingThreadsAndClosedDispatcherCannotRestart() {
        assertThrows(IllegalArgumentException.class, () -> new GenerationDispatcher(p -> {}, Duration.ZERO, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GenerationDispatcher(p -> {}, Duration.ofSeconds(1), 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GenerationDispatcher(p -> {}, Duration.ofSeconds(1), 1, 17, 1));
        var dispatcher = new GenerationDispatcher(p -> {}, Duration.ofSeconds(1), 1, 1, 1);
        dispatcher.close();dispatcher.close();
        assertThrows(IllegalStateException.class, dispatcher::start);
    }
}
