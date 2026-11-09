package com.qingfeng.music.media.application;
import java.io.*;
public interface MediaFileStore {
    Saved save(InputStream input)throws IOException;
    InputStream open(String key)throws IOException;
    default InputStream open(String key,long offset)throws IOException{var input=open(key);try{input.skipNBytes(offset);return input;}catch(IOException e){input.close();throw e;}}
    boolean exists(String key);
    void discard(String key)throws IOException;
    record Saved(String key,long size,String contentType,String sha256) {}
}
