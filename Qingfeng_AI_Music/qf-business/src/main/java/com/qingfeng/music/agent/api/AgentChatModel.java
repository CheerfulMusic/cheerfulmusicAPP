package com.qingfeng.music.agent.api;

import java.util.List;
import java.util.Map;

/** The model proposes a bounded plan; the application independently authorizes every step. */
public interface AgentChatModel {
    record Turn(String role, String text) {}
    /** Server-authenticated execution identity; never accept these values from model output. */
    record Execution(String owner, String conversation, String requestKey) {}
    record Context(String text, String locale, List<Turn> history, SongCard song, String settings,
                   List<AgentApplicationActions.Capability> capabilities, String results, Execution execution) {
        public Context {
            history = history == null ? List.of() : List.copyOf(history);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        }
        public Context(String text,String locale,List<Turn> history,SongCard song,String settings,
                       List<AgentApplicationActions.Capability> capabilities,String results) {
            this(text,locale,history,song,settings,capabilities,results,null);
        }
        public Context(String text,String locale,List<Turn> history,SongCard song,String settings) {
            this(text,locale,history,song,settings,List.of(),null);
        }
        public Context(String text,String locale,List<Turn> history,SongCard song){this(text,locale,history,song,null);}
        public Context withExecution(Execution value) {
            return new Context(text,locale,history,song,settings,capabilities,results,value);
        }
    }
    record Step(String action, Map<String, Object> args) {
        public Step { args = immutableArgs(args); }
    }
    record Plan(String action, Map<String, Object> args, String reply, List<Step> steps) {
        public Plan {
            args = immutableArgs(args);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
        public Plan(String action,Map<String,Object> args,String reply){this(action,args,reply,List.of());}
        public List<Step> orderedSteps() {
            return steps.isEmpty() ? List.of(new Step(action == null ? "chat" : action,args)) : steps;
        }
    }
    private static Map<String,Object> immutableArgs(Map<String,Object> args) {
        // Optional JSON fields may explicitly contain null.
        return args == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(args));
    }
    Plan respond(Context context);
}
