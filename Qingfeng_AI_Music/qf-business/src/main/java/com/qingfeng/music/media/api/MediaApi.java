package com.qingfeng.music.media.api;
import java.io.*;
import java.time.Instant;
import java.util.List;
public interface MediaApi {
    Asset importAudio(String creator,String work,InputStream content);
    List<Asset> list(String creator,String work);
    Asset get(String creator,String work,String asset);
    Content open(String creator,String work,String asset);
    default boolean matchesGenerationRevision(String owner,String work,String asset,String revision){return false;}
    default Selection selection(String owner,String work){return new Selection(null,0,null);}
    default Selection select(String owner,String work,String asset,long version){throw new UnsupportedOperationException();}
    default void keepOriginal(String owner,String work,String asset){}
    record Selection(String assetId,long version,Asset asset){}
    record Asset(String id,String workId,String origin,String contentType,long byteSize,String sha256,Instant createdAt,boolean sectionEdit) {
        public Asset(String id,String work,String origin,String type,long bytes,String sha,Instant at){this(id,work,origin,type,bytes,sha,at,false);}
    }
    record Content(long size,String contentType,String filename,Source source) {}
    @FunctionalInterface interface Source{InputStream open()throws IOException;}
}
