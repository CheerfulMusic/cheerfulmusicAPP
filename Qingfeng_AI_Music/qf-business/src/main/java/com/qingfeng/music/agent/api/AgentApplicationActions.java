package com.qingfeng.music.agent.api;

import java.util.List;
import java.util.Map;

/** User-facing application operations exposed through the conversation, never arbitrary HTTP calls. */
public interface AgentApplicationActions {
    record Capability(String id, String description, String parameters, String effect) {}
    record Item(String id, String title, String subtitle, Map<String,Object> data) {}
    record Command(String label, String text, String kind) {}
    record Result(String reply, String kind, List<Item> items, List<Command> commands, String action) {
        public Result { items = items == null ? List.of() : List.copyOf(items); commands = commands == null ? List.of() : List.copyOf(commands); }
    }
    default List<Capability> capabilities() { return List.of(); }
    default boolean supports(String action) { return capabilities().stream().anyMatch(c -> c.id().equals(action)); }
    default Map<String,Object> prepare(String owner, String conversation, String action, Map<String,Object> args, SongCard current) { return args; }
    Result execute(String owner, String conversation, String action, Map<String,Object> args,
                   String text, String requestKey, SongCard current);
}
