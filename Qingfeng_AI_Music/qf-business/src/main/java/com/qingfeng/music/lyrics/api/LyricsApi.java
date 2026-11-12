package com.qingfeng.music.lyrics.api;
import java.time.Instant;
import java.util.List;
public interface LyricsApi {
    Revision save(String creator,String work,long workVersion,String title,String lyrics);
    default Revision save(String creator,String work,long workVersion,String title,String lyrics,String baseRevisionId) {
        return save(creator,work,workVersion,title,lyrics);
    }
    Revision confirm(String creator,String work,String revision);
    Revision get(String creator,String work,String revision);
    List<Revision> list(String creator,String work);
    record Revision(String id,String workId,long workVersion,String title,String lyrics,String source,boolean confirmed,
                    Instant createdAt,String language,String templateId,Boolean chorusFirst,List<String> customSections) {
        public Revision {
            customSections = customSections == null ? null : List.copyOf(customSections);
        }
        public Revision(String id,String workId,long workVersion,String title,String lyrics,String source,boolean confirmed,
                        Instant createdAt,String language,String templateId,Boolean chorusFirst) {
            this(id,workId,workVersion,title,lyrics,source,confirmed,createdAt,language,templateId,chorusFirst,null);
        }
        public Revision(String id,String workId,long workVersion,String title,String lyrics,String source,boolean confirmed,Instant createdAt) {
            this(id,workId,workVersion,title,lyrics,source,confirmed,createdAt,null,null,null);
        }
    }
}
