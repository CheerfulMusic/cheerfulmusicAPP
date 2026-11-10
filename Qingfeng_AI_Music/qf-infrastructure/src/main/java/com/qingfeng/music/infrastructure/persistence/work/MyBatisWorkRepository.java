package com.qingfeng.music.infrastructure.persistence.work;

import com.qingfeng.music.work.api.WorkApi.Work;
import com.qingfeng.music.work.application.WorkRepository;
import java.util.*;

public class MyBatisWorkRepository implements WorkRepository {
    private final WorkMapper mapper;
    public MyBatisWorkRepository(WorkMapper mapper) { this.mapper=mapper; }
    public boolean lockActiveOwner(String owner){return mapper.lockOwner(owner)!=null&&!mapper.activeSessions(owner).isEmpty();}
    public void insert(String owner,Work work) { mapper.insert(row(owner,work)); }
    public boolean update(String owner,Work work,long expected) { return mapper.update(row(owner,work),expected)==1; }
    public Optional<Work> find(String owner,String id) { return Optional.ofNullable(mapper.find(owner,id)).map(this::work); }
    public List<Work> list(String owner,int offset,int limit) { return mapper.list(owner,offset,limit).stream().map(this::work).toList(); }
    public void delete(String owner,String id) {
        mapper.deleteSectionEdits(id);
        mapper.deleteGenerationAudioSources(id);
        mapper.deleteGenerationMusicPreferences(id);
        mapper.deleteGenerationAudioCandidates(id);
        // generation_attempt 挂在 generation_task 上，必须先删，否则删除作品会因外键约束失败。
        mapper.deleteGenerationAttempts(id);
        mapper.deleteAudioSelection(id);
        mapper.deleteShares(id);
        mapper.deleteLyricsTimelines(id);
        mapper.deleteListeningFeedback(id);
        mapper.deleteCoverEditTasks(id);
        mapper.deleteWorkCover(id);
        mapper.deleteLyricCreativePlans(id);
        mapper.deleteGenerationTasks(id);
        mapper.deleteLyricRevisions(id);
        mapper.deleteMediaAssets(id);
        mapper.deleteCoverAssets(id);
        mapper.deleteModelUsageReservations(id);
        mapper.deleteLyricsCompositionRequests(id);
        mapper.deleteWorkMusicPreferences(id);
        // 兜底清理尚未入库迁移引入的子表（quick_creation），存在才删。
        if(mapper.countExistingTable("quick_creation")>0)mapper.deleteQuickCreations(id);
        mapper.deleteWork(owner,id);
    }
    private Work work(WorkRow r) { return new Work(r.id,r.recipient,r.occasion,r.story,r.message,r.style,r.title,r.version,r.createdAt,r.updatedAt,r.vocalGender); }
    private WorkRow row(String owner,Work w) { WorkRow r=new WorkRow();r.id=w.id();r.ownerId=owner;r.recipient=w.recipient();r.occasion=w.occasion();r.story=w.story();r.message=w.message();r.style=w.style();r.title=w.title();r.version=w.version();r.createdAt=w.createdAt();r.updatedAt=w.updatedAt();r.vocalGender=w.vocalGender();return r; }
}
