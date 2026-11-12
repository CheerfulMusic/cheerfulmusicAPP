package com.qingfeng.music.agent.api;

import java.util.List;
import java.util.Map;

/** Conversational model plans one action; only the application service executes it. */
public interface AgentChatModel {
    record Turn(String role, String text) {}
    record Context(String text, String locale, List<Turn> history, SongCard song, String settings) {
        public Context(String text,String locale,List<Turn> history,SongCard song){this(text,locale,history,song,null);}
    }
    record Plan(String action, Map<String, Object> args, String reply) {}
    Plan respond(Context context);
}
