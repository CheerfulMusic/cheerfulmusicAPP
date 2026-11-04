package com.qingfeng.music.media.application;
import com.qingfeng.music.media.api.MediaApi.Asset;
import java.util.*;
public interface MediaRepository extends com.qingfeng.music.media.api.MediaCapacity {
    default com.qingfeng.music.media.api.MediaApi.Selection selection(String work){return new com.qingfeng.music.media.api.MediaApi.Selection(null,0,null);}
    default boolean select(String work,String asset,long version){return false;}
    default void keepOriginal(String work,String asset){}
    default java.util.Optional<String> generatedRevision(String work,String asset){return java.util.Optional.empty();}
    void insert(Asset asset,String storageKey);
    Optional<Stored> find(String work,String id);
    List<Asset> list(String work);
    Optional<Stored> findGenerated(String task);
    /** 唯一任务键保证归档重复执行最多登记一份；返回最终有效的文件。 */
    Stored insertGenerated(Asset asset,String storageKey,String task);
    boolean reserve(String work,long bytes,long capacity);
    void release(String work,long bytes);
    /** 文件已经写完；实现必须原子登记、选用与结算容量。提交未知不能补偿删除。 */
    default Stored register(Asset asset,String key,String task,long reserved){
        Stored effective;
        if(task==null){insert(asset,key);effective=new Stored(asset,key);}else effective=insertGenerated(asset,key,task);
        release(asset.workId(),reserved-(effective.storageKey().equals(key)?asset.byteSize():0));return effective;
    }
    class RegistrationFailure extends RuntimeException {
        private final boolean rolledBack;
        public RegistrationFailure(RuntimeException cause,boolean rolledBack){super("音频登记未确认",cause);this.rolledBack=rolledBack;}
        public boolean rolledBack(){return rolledBack;}
    }
    record Stored(Asset asset,String storageKey) {}
}
