package com.qingfeng.music.server.config;

import com.qingfeng.music.agent.application.PendingIntentDispatcher;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 排队意图的定时触发。仅当 qingfeng.agent.chat.enabled=true 时由 AgentChatConfiguration 注册。
 * 复用 GenerationWorker 同款 3 秒节奏，不引入消息队列。
 */
public class PendingIntentScheduler {

    private final PendingIntentDispatcher dispatcher;

    public PendingIntentScheduler(PendingIntentDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelay = 3000)
    public void scan() {
        dispatcher.processOnce();
    }
}
