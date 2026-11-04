package com.qingfeng.music.usage;

/** 在付费请求进入队列前预留次数；供应商结果不明确时不退还额度。 */
public interface UsageBudget {
    enum Kind { MUSIC, LYRICS, TIMING, COVER }
    void reserve(String owner, String work, String requestKey, Kind kind);
    /** 必须先锁准入行，再原子撤销尚未提交的任务；避免与生成准入形成逆向锁序。 */
    default boolean cancelMusic(String work,String requestKey,java.util.function.BooleanSupplier cancel){return cancel.getAsBoolean();}
}
