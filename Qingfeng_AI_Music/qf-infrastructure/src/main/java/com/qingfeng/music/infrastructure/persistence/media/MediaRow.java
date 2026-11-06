package com.qingfeng.music.infrastructure.persistence.media;
import java.time.Instant;
public class MediaRow {
    public String id;
    public String workId;
    public String storageKey;
    public String origin;
    public String contentType;
    public long byteSize;
    public String sha256;
    public Instant createdAt;
    public boolean sectionEdit;
    public String generationTaskId,candidateKey,providerAudioId,sourceAudioUrl;
    public Double durationS;
}
