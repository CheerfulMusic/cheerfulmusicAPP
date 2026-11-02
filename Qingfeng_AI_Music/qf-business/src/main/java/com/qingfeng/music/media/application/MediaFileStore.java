package com.qingfeng.music.media.application;
import java.io.*;
public interface MediaFileStore {
    Saved save(InputStream input)throws IOException;
    InputStream open(String key)throws IOException;
    boolean exists(String key);
    void discard(String key)throws IOException;
    record Saved(String key,long size,String contentType,String sha256) {}
}
