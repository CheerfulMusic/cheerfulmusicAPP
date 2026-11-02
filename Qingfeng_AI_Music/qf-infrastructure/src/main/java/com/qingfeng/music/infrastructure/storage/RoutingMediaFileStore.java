package com.qingfeng.music.infrastructure.storage;
import com.qingfeng.music.media.application.MediaFileStore;
import com.qingfeng.music.common.exception.DomainException;
import java.io.*;
/** 存储类型随资源写入，不因切换新上传位置而破坏历史本地音频。 */
public final class RoutingMediaFileStore implements MediaFileStore {
    private final MediaFileStore local,oss; private final boolean cloud;
    public RoutingMediaFileStore(MediaFileStore local,MediaFileStore oss,boolean cloud){if(cloud&&oss==null)throw new IllegalArgumentException("OSS尚未配置");this.local=local;this.oss=oss;this.cloud=cloud;}
    public Saved save(InputStream input)throws IOException{var saved=(cloud?oss:local).save(input);return new Saved((cloud?"oss:":"local:")+saved.key(),saved.size(),saved.contentType(),saved.sha256());}
    private MediaFileStore store(String key){if(key.startsWith("oss:")){if(oss==null)throw new DomainException("MEDIA_STORAGE_UNAVAILABLE","此音频需要已配置的OSS存储");return oss;}return local;}
    private String value(String key){return key.startsWith("oss:")?key.substring(4):key.startsWith("local:")?key.substring(6):key;}
    public InputStream open(String key)throws IOException{return store(key).open(value(key));}
    public boolean exists(String key){return store(key).exists(value(key));}
    public void discard(String key)throws IOException{store(key).discard(value(key));}
}
