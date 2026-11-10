package com.qingfeng.music.server.query;

import com.qingfeng.music.work.api.WorkApi;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 作品列表的只读投影，一次批量读取当前页状态，不逐个作品查询三个子模块。 */
@Component @Profile({"mysql","test"})
public final class WorkLibraryQuery {
    public record Item(WorkApi.Work work,String status,String audioId,String audioOrigin,String songTitle,String coverId) {}
    private final WorkApi works;
    private final JdbcTemplate jdbc;
    public WorkLibraryQuery(WorkApi works,JdbcTemplate jdbc){this.works=works;this.jdbc=jdbc;}
    public List<Item> list(String owner,int page){
        var owned=works.list(owner,page);if(owned.isEmpty())return List.of();
        String placeholders=String.join(",",Collections.nCopies(owned.size(),"?"));
        String sql="""
            SELECT w.id,
              (SELECT q.status FROM quick_creation q WHERE q.work_id=w.id ORDER BY q.created_at DESC,q.id DESC LIMIT 1) AS quick_status,
              (SELECT t.status FROM generation_task t WHERE t.work_id=w.id ORDER BY t.created_at DESC,t.id DESC LIMIT 1) AS task_status,
              (SELECT COUNT(*) FROM generation_task t WHERE t.work_id=w.id AND t.status IN ('QUEUED','SUBMITTING','RUNNING','SAVING')) AS active_tasks,
              (SELECT COUNT(*) FROM generation_task t WHERE t.work_id=w.id AND t.status='SUBMISSION_UNKNOWN') AS unknown_tasks,
              (SELECT COUNT(*) FROM lyric_revision l WHERE l.work_id=w.id AND l.work_version=w.version) AS lyric_count,
              c.cover_id,m.id AS audio_id,m.origin AS audio_origin,
              COALESCE(t.title,NULLIF(w.title,''),(SELECT l.title FROM lyric_revision l WHERE l.work_id=w.id ORDER BY l.created_at DESC,l.id DESC LIMIT 1)) AS song_title
            FROM music_work w
            LEFT JOIN work_cover c ON c.work_id=w.id
            LEFT JOIN media_asset m ON m.id=COALESCE((SELECT s.asset_id FROM work_audio_selection s WHERE s.work_id=w.id),(SELECT a.id FROM media_asset a WHERE a.work_id=w.id AND NOT EXISTS(SELECT 1 FROM section_edit e WHERE e.task_id=a.generation_task_id) ORDER BY a.created_at DESC,a.id DESC LIMIT 1))
            LEFT JOIN generation_task t ON t.id=m.generation_task_id
            WHERE w.owner_id=? AND w.deleted_at IS NULL AND w.id IN (
            """+placeholders+")";
        var args=new ArrayList<Object>();args.add(owner);owned.forEach(w->args.add(w.id()));
        var byId=new HashMap<String,State>();
        jdbc.query(sql, row->{
            String quick=row.getString("quick_status");
            String status=row.getInt("active_tasks")>0||List.of("QUEUED","WRITING","LYRICS_READY","SUBMITTING_MUSIC").contains(quick==null?"":quick)?"GENERATING":row.getInt("unknown_tasks")>0||"SUBMISSION_UNKNOWN".equals(quick)?"CHECKING":row.getString("audio_id")!=null?"COMPLETED":"FAILED".equals(row.getString("task_status"))||"FAILED".equals(quick)?"GENERATION_FAILED":row.getInt("lyric_count")>0?"READY_TO_GENERATE":"LYRICS_DRAFT";
            byId.put(row.getString("id"),new State(status,row.getString("audio_id"),row.getString("audio_origin"),row.getString("song_title"),row.getString("cover_id")));
        },args.toArray());
        return owned.stream().filter(work->byId.containsKey(work.id())).map(work->{var s=byId.get(work.id());return new Item(work,s.status,s.audioId,s.origin,s.title,s.coverId);}).toList();
    }
    private record State(String status,String audioId,String origin,String title,String coverId){}
}
