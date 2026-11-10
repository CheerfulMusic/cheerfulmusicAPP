package com.qingfeng.music.lyrics.application;
import java.util.Optional;
public interface CompositionRequests {
    record Entry(String status,String revisionId,String requestHash,String errorCode) {
        public Entry(String status,String revisionId,String requestHash){this(status,revisionId,requestHash,null);}
        public Entry(String status,String revisionId){this(status,revisionId,null);}
    }
    Optional<Entry> find(String work,String key);
    boolean claim(String work,String key);
    default boolean claim(String owner,String work,String key,String hash){return claim(work,key);}
    void complete(String work,String key,String revision);
    default void completeWithRevision(String work,String key,String revision,Runnable save){save.run();complete(work,key,revision);}
    void failed(String work,String key);
    default void failed(String work,String key,String code){failed(work,key);}
    void unknown(String work,String key,String code);
}
