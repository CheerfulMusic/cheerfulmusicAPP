package com.qingfeng.music.server.mv;

import java.nio.file.Path;

/** Narrow asynchronous video contract, implemented by NewAPI's Seedance-compatible /videos API. */
public interface VideoProvider {
    boolean available();
    String submit(String prompt,int seconds,String ratio,String resolution,String requestId);
    String status(String taskId);
    void download(String taskId,Path destination);
    final class Failure extends RuntimeException {
        public final boolean uncertain;
        public final String code;
        public Failure(String code,boolean uncertain){super(code);this.code=code;this.uncertain=uncertain;}
    }
}
