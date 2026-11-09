package com.qingfeng.music.infrastructure.storage;

import com.qingfeng.music.media.application.MediaFileStore;
import com.qingfeng.music.common.exception.DomainException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import java.io.*;

/** 先在本地暂存并验证容器及大小，再上传私有对象；播放仍经过应用授权。 */
public final class OssMediaFileStore implements MediaFileStore {
    private final OSS client;
    private final String bucket,prefix;
    private final LocalMediaFileStore staging;
    public OssMediaFileStore(OSS client,String bucket,String prefix,LocalMediaFileStore staging){
        if(!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")||!prefix.matches("[a-zA-Z0-9/_-]{1,100}/")||prefix.contains(".."))throw new IllegalArgumentException("OSS存储配置无效");
        this.client=client;this.bucket=bucket;this.prefix=prefix;this.staging=staging;
    }
    public Saved save(InputStream input)throws IOException{
        var saved=staging.save(input);
        try(var content=staging.open(saved.key())){
            var metadata=new ObjectMetadata();metadata.setContentLength(saved.size());metadata.setContentType(saved.contentType());
            metadata.setObjectAcl(CannedAccessControlList.Private);
            var request=new PutObjectRequest(bucket,key(saved.key()),content,metadata);
            client.putObject(request);return saved;
        }catch(RuntimeException e){throw new IOException("云端音频保存失败");}
        finally{staging.discard(saved.key());}
    }
    public InputStream open(String value)throws IOException{
        try{var object=client.getObject(bucket,key(value));return new FilterInputStream(object.getObjectContent()){
            public void close()throws IOException{object.close();}
        };}catch(RuntimeException e){throw new IOException("云端音频暂不可用");}
    }
    public InputStream open(String value,long offset)throws IOException{
        try{var request=new GetObjectRequest(bucket,key(value));request.setRange(offset,-1);
            var object=client.getObject(request);return new FilterInputStream(object.getObjectContent()){
                public void close()throws IOException{object.close();}
            };
        }catch(RuntimeException e){throw new IOException("云端音频暂不可用",e);}
    }
    public boolean exists(String value){try{return client.doesObjectExist(bucket,key(value));}catch(RuntimeException e){throw new DomainException("MEDIA_STORAGE_UNAVAILABLE","云端音频暂不可用");}}
    public void discard(String value)throws IOException{try{client.deleteObject(bucket,key(value));}catch(RuntimeException e){throw new IOException("云端音频清理失败");}}
    private String key(String value){if(value==null||!value.matches("[0-9a-f-]{36}\\.(wav|mp3)"))throw new IllegalArgumentException("非法存储标识");return prefix+value;}
}
