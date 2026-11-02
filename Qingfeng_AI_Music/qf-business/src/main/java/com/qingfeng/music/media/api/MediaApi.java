package com.qingfeng.music.media.api;
import java.io.*;
import java.time.Instant;
import java.util.List;
public interface MediaApi {
    Asset importAudio(String creator,String work,InputStream content);
    List<Asset> list(String creator,String work);
    Asset get(String creator,String work,String asset);
    Content open(String creator,String work,String asset);
    record Asset(String id,String workId,String origin,String contentType,long byteSize,String sha256,Instant createdAt) {}
    record Content(long size,String contentType,String filename,Source source) {}
    @FunctionalInterface interface Source{InputStream open()throws IOException;}
}
