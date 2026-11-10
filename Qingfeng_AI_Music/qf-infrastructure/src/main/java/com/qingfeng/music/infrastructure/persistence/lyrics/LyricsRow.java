package com.qingfeng.music.infrastructure.persistence.lyrics;
import java.time.Instant;
public class LyricsRow {
    public String id;
    public String workId;
    public long workVersion;
    public String title;
    public String lyrics;
    public String source;
    public boolean confirmed;
    public Instant createdAt;
    public String language;
    public String templateId;
    public Boolean chorusFirst;
}
