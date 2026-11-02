package com.qingfeng.music.lyrics.application;
import java.util.Optional;
public interface CompositionRequests {
    record Entry(String status,String revisionId) {}
    Optional<Entry> find(String work,String key);
    boolean claim(String work,String key);
    void complete(String work,String key,String revision);
    void failed(String work,String key);
}
