package com.qingfeng.music.lyrics.application;
import com.qingfeng.music.lyrics.api.LyricsApi.Revision;
import java.util.*;
public interface LyricsRepository {
    void insert(Revision revision);
    Optional<Revision> find(String work,String id);
    List<Revision> list(String work);
    void confirm(String work,String id);
}
