package com.qingfeng.music.server.ai;

import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Counts;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Lane;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;

public final class AiCapacityMetrics {
    private final JdbcAiWorkQueue queue;
    private final Map<Lane, AtomicReference<Counts>> readings = new EnumMap<>(Lane.class);

    public AiCapacityMetrics(JdbcAiWorkQueue queue, MeterRegistry meters) {
        this.queue = queue;
        for (Lane lane : Lane.values()) {
            var reading = new AtomicReference<>(new Counts(0, 0, 0));
            readings.put(lane, reading);
            Gauge.builder("qingfeng.ai.capacity.queued", reading, value -> value.get().queued()).tag("lane", lane.name()).register(meters);
            Gauge.builder("qingfeng.ai.capacity.executing", reading, value -> value.get().running()).tag("lane", lane.name()).register(meters);
            Gauge.builder("qingfeng.ai.capacity.unknown", reading, value -> value.get().unknown()).tag("lane", lane.name()).register(meters);
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void sample() {
        readings.forEach((lane, reading) -> reading.set(queue.counts(lane)));
    }
}
