package com.qingfeng.music.server.mv;

@FunctionalInterface
public interface AudioDuration {
    long millis(String owner, String work, String asset);
}
