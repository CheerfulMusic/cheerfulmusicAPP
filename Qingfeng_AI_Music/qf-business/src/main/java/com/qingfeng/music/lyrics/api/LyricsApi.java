package com.qingfeng.music.lyrics.api;
import java.time.Instant;
import java.util.List;
public interface LyricsApi {
    Revision save(String creator,String work,long workVersion,String title,String lyrics);
    Revision confirm(String creator,String work,String revision);
    Revision get(String creator,String work,String revision);
    List<Revision> list(String creator,String work);
    record Revision(String id,String workId,long workVersion,String title,String lyrics,String source,boolean confirmed,Instant createdAt) {}
}
