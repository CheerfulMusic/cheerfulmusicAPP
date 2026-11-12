package com.qingfeng.music.server.agent;

import java.time.Duration;

public interface AgentAdmission {
    record Limits(int globalConcurrent,int userConcurrent,int globalRpm,int userRpm,long globalDailyTokens,long userDailyTokens,int maxPromptBytes,Duration lease) {
        public Limits {if(globalConcurrent<1||userConcurrent<1||globalRpm<1||userRpm<1||maxPromptBytes<1024||maxPromptBytes>262144||globalDailyTokens<1||userDailyTokens<1||lease.isNegative()||lease.isZero())throw new IllegalArgumentException("Invalid Agent admission limits");}
        public long reservationTokens(){return 2L*(maxPromptBytes+2600);}
    }
    record Ticket(String id,String owner,boolean replay) {}
    Ticket acquire(String owner,String conversation,String key,String text,String locale);
    void start(Ticket ticket);
    void heartbeat(Ticket ticket);
    void finish(Ticket ticket,boolean completed);
    /** Only before any business execution; removes its token reservation. */
    void abandon(Ticket ticket);
}
