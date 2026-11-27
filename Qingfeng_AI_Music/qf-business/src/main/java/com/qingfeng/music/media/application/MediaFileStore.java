package com.qingfeng.music.media.application;
import java.io.*;
public interface MediaFileStore {
    Saved save(InputStream input)throws IOException;
    default Saved save(InputStream input,java.util.function.BiConsumer<Stage,Long> observe)throws IOException{return save(input);}
    enum Stage { VALIDATE, STAGE_FILE, OSS_UPLOAD, REGISTER }
    final class UploadFailure extends IOException {
        public UploadFailure(Throwable cause){super("云端音频保存失败",cause);}
    }
    InputStream open(String key)throws IOException;
    /** Check readable bytes without transferring a whole remote recording. */
    default boolean readable(String key)throws IOException{try(var input=open(key)){return input.read()!=-1;}}
    default InputStream open(String key,long offset)throws IOException{var input=open(key);try{input.skipNBytes(offset);return input;}catch(IOException e){input.close();throw e;}}
    boolean exists(String key);
    void discard(String key)throws IOException;
    record Saved(String key,long size,String contentType,String sha256) {}
}
