package com.qingfeng.music.infrastructure.persistence.sharing;
import com.qingfeng.music.sharing.application.ShareRepository;
import java.time.Instant;
import java.util.*;
public class MyBatisShareRepository implements ShareRepository {
 private final ShareMapper mapper;
 public MyBatisShareRepository(ShareMapper mapper){this.mapper=mapper;}
 public void insert(Snapshot s){var r=new ShareRow();r.id=s.id();r.workId=s.workId();r.tokenHash=s.tokenHash();r.assetId=s.assetId();r.title=s.title();r.recipient=s.recipient();r.message=s.message();r.lyrics=s.lyrics();r.audioOrigin=s.audioOrigin();r.active=s.active();r.createdAt=s.createdAt();r.expiresAt=s.expiresAt();r.coverId=s.coverId();mapper.insert(r);}
 private Snapshot snapshot(ShareRow r){return new Snapshot(r.id,r.workId,r.tokenHash,r.assetId,r.title,r.recipient,r.message,r.lyrics,r.audioOrigin,r.active,r.createdAt,r.expiresAt,r.coverId);}
 public Optional<Snapshot> active(String hash,Instant now){return Optional.ofNullable(mapper.active(hash,now)).map(this::snapshot);}
 public void revokeAll(String work){mapper.revokeAll(work);}
}
