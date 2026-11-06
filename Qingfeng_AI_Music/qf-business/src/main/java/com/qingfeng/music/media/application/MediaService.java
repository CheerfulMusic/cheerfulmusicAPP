package com.qingfeng.music.media.application;

import com.qingfeng.music.media.api.*;
import com.qingfeng.music.generation.application.port.GeneratedAudioArchive;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.common.exception.DomainException;
import java.io.*;
import java.time.Instant;
import java.util.*;

public class MediaService implements MediaApi, SharedMediaAccess, GeneratedAudioArchive {
    private static final long MAX_AUDIO=20L*1024*1024;
    private final WorkApi works;
    private final MediaRepository repository;
    private final MediaFileStore files;
    private final long capacity;
    public MediaService(WorkApi works,MediaRepository repository,MediaFileStore files,long capacity){
        if(capacity<MAX_AUDIO)throw new IllegalArgumentException("个人容量不能小于单文件上限");
        this.works=works;this.repository=repository;this.files=files;this.capacity=capacity;
    }
    public Asset importAudio(String owner,String work,InputStream input){works.get(owner,work);return save(work,"IMPORTED",null,input);}
    public String store(String task,String work,InputStream input){
        var existing=repository.findGenerated(task);
        if(existing.isPresent())return existing.get().asset().id();
        return save(work,"GENERATED",task,input).id();
    }
    public Optional<String> existingCandidate(String task,String candidate){return repository.findGenerated(task,candidate).map(s->s.asset().id());}
    public String storeCandidate(String task,String work,String candidate,com.qingfeng.music.generation.application.port.MusicProvider.AudioInfo source,InputStream input){
        var existing=repository.findGenerated(task,candidate);
        if(existing.isPresent())return existing.get().asset().id();
        return save(work,"GENERATED",task,input,candidate,source).id();
    }
    private Asset save(String work,String origin,String task,InputStream input){
        return save(work,origin,task,input,"primary",null);
    }
    private Asset save(String work,String origin,String task,InputStream input,String candidate,com.qingfeng.music.generation.application.port.MusicProvider.AudioInfo source){
        // 先原子预留单文件最大容量，阻止并发上传绕过总量限制；只结算实际登记成功的字节。
        if(!repository.reserve(work,MAX_AUDIO,capacity))throw new DomainException("STORAGE_QUOTA_EXCEEDED","作品音频容量不足，请联系维护人员扩容");
        MediaFileStore.Saved saved=null;boolean registering=false,rollback=false;MediaRepository.Stored effective=null;
        try{
            saved=files.save(input);
            var asset=new Asset(UUID.randomUUID().toString(),work,origin,saved.contentType(),saved.size(),saved.sha256(),Instant.now());
            registering=true;
            try{effective=task==null?repository.register(asset,saved.key(),null,MAX_AUDIO):repository.registerCandidate(asset,saved.key(),task,MAX_AUDIO,candidate,source);}
            catch(MediaRepository.RegistrationFailure error){rollback=error.rolledBack();throw error;}
            return effective.asset();
        }catch(IOException e){throw new DomainException("MEDIA_STORAGE_UNAVAILABLE","音频保存失败，可以稍后重试");}
        finally{
            // 只有从未登记或事务明确回滚时才退额并删文件；提交未知保留文件与预留供核对。
            boolean compensate=!registering||rollback;
            try{if(saved!=null&&(compensate||effective!=null&&!effective.storageKey().equals(saved.key())))files.discard(saved.key());}
            catch(IOException cleanup){/* 孤儿文件由停机维护扫描隔离，避免覆盖原始数据库错误。 */}
            finally{if(compensate)repository.release(work,MAX_AUDIO);}
        }
    }
    public Asset get(String owner,String work,String id){works.get(owner,work);return find(work,id).asset();}
    public boolean matchesGenerationRevision(String owner,String work,String asset,String revision){works.get(owner,work);return repository.generatedRevision(work,asset).filter(revision::equals).isPresent();}
    public List<Asset> list(String owner,String work){works.get(owner,work);return repository.list(work);}
    public Selection selection(String owner,String work){works.get(owner,work);var value=repository.selection(work);return new Selection(value.assetId(),value.version(),value.assetId()==null?null:find(work,value.assetId()).asset());}
    public Selection select(String owner,String work,String asset,long version){works.get(owner,work);find(work,asset);if(!repository.select(work,asset,version))throw new DomainException("AUDIO_SELECTION_CONFLICT","当前歌曲版本已改变，请刷新后再选择。");return selection(owner,work);}
    public void keepOriginal(String owner,String work,String asset){works.get(owner,work);find(work,asset);repository.keepOriginal(work,asset);}
    public Content open(String owner,String work,String id){works.get(owner,work);return content(work,id);}
    public Content content(String work,String id){
        var stored=find(work,id);if(!files.exists(stored.storageKey()))throw new DomainException("MEDIA_NOT_FOUND","音频文件不存在，请联系维护人员恢复备份");
        var asset=stored.asset();return new Content(asset.byteSize(),asset.contentType(),asset.id()+(asset.contentType().equals("audio/wav")?".wav":".mp3"),()->files.open(stored.storageKey()));
    }
    private MediaRepository.Stored find(String work,String id){return repository.find(work,id).orElseThrow(()->new DomainException("MEDIA_NOT_FOUND","音频不存在"));}
}
