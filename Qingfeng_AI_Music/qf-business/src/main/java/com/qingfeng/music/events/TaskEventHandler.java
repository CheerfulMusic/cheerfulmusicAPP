package com.qingfeng.music.events;

/** A notification wakes a durable database worker; the worker still claims and fences its task. */
public interface TaskEventHandler {
    String topic();
    void handle(DurableEvents.Event event);
}
