package com.qingfeng.music.server.ai;

import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Lane;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Limits;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Stage;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import org.springframework.core.env.Environment;

public final class AiCapacity {
    private final Environment environment;
    private final EnumSet<Lane> workers = EnumSet.noneOf(Lane.class);

    public AiCapacity(Environment environment) {
        this.environment = environment;
        Arrays.stream(environment.getProperty("qingfeng.ai.capacity.worker-lanes", "").split(","))
            .map(String::trim).filter(lane -> !lane.isEmpty())
            .map(lane -> Lane.valueOf(lane.toUpperCase(Locale.ROOT))).forEach(workers::add);
    }

    public boolean runs(Lane lane) {
        return workers.contains(lane);
    }

    public Limits limits(Lane lane) {
        return new Limits(value(lane, "running", 1000), value(lane, "backlog", 200),
            Duration.ofSeconds(value(lane, "queue-timeout-seconds", lane == Lane.AGENT ? 35 : 120)));
    }

    int threads(Lane lane, Stage stage) {
        int count = value(lane, stage.name().toLowerCase(Locale.ROOT) + "-threads", stage == Stage.START ? 32 : 8);
        if (count < 1 || count > 256) throw new IllegalArgumentException("Invalid AI worker thread count");
        return count;
    }

    private int value(Lane lane, String key, int fallback) {
        return environment.getProperty("qingfeng.ai.capacity." + lane.name().toLowerCase(Locale.ROOT) + "." + key,
            Integer.class, fallback);
    }
}
