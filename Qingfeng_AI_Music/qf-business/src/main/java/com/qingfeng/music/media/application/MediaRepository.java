package com.qingfeng.music.media.application;
import com.qingfeng.music.media.api.MediaApi.Asset;
import java.util.*;
public interface MediaRepository {
    void insert(Asset asset,String storageKey);
    Optional<Stored> find(String work,String id);
    List<Asset> list(String work);
    Optional<Stored> findGenerated(String task);
    /** 唯一任务键保证归档重复执行最多登记一份；返回最终有效的文件。 */
    Stored insertGenerated(Asset asset,String storageKey,String task);
    boolean reserve(String work,long bytes,long capacity);
    void release(String work,long bytes);
    record Stored(Asset asset,String storageKey) {}
}
