package com.qingfeng.music.server.query;

import com.qingfeng.music.work.api.WorkApi;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 作品列表的只读投影，一次批量读取当前页状态，不逐个作品查询三个子模块。 */
@Component @Profile({"mysql","test"})
public final class WorkLibraryQuery {
    public record Item(WorkApi.Work work,String status,String audioId,String audioOrigin,String songTitle,String coverId,String videoId,String videoState,WorkSearchEvidence evidence) {}
    private final WorkApi works;
    private final JdbcTemplate jdbc;
    public WorkLibraryQuery(WorkApi works,JdbcTemplate jdbc){this.works=works;this.jdbc=jdbc;}
    public List<Item> list(String owner,int page){
        return project(owner,works.list(owner,page),Map.of());
    }
    /** Literal keyword query, scoped before pagination. User '%' and '_' never become wildcards. */
    public List<Item> search(String owner,String query,int page) {
        if(query==null||query.isBlank())return list(owner,page);
        if(page<0||page>10000||query.length()>200)throw new com.qingfeng.music.common.exception.DomainException("INVALID_SEARCH","关键词最多200字，页码必须有效。");
        String pattern="%"+query.trim().replace("!","!!").replace("%","!%").replace("_","!_").toLowerCase(Locale.ROOT)+"%";
        String keyword=query.strip();
        String exact=keyword.toLowerCase(Locale.ROOT);
        var matches=jdbc.query("""
            SELECT w.id,w.version,w.title,w.story,w.message,l.id AS revision_id,l.title AS lyric_title,l.lyrics,
              (SELECT COUNT(*) FROM lyric_revision v WHERE v.work_id=l.work_id
               AND (v.created_at<l.created_at OR (v.created_at=l.created_at AND v.id<=l.id))) AS lyric_number
            FROM music_work w LEFT JOIN lyric_revision l ON l.id=(
              SELECT r.id FROM lyric_revision r WHERE r.work_id=w.id
              AND (LOWER(r.lyrics) LIKE ? ESCAPE '!' OR LOWER(r.title) LIKE ? ESCAPE '!')
              ORDER BY CASE WHEN LOWER(r.title)=? THEN 0 WHEN LOWER(r.lyrics) LIKE ? ESCAPE '!' THEN 1 ELSE 2 END,
                r.created_at DESC,r.id DESC LIMIT 1)
            WHERE w.owner_id=? AND w.deleted_at IS NULL AND (l.id IS NOT NULL
              OR LOWER(w.title) LIKE ? ESCAPE '!' OR LOWER(w.story) LIKE ? ESCAPE '!' OR LOWER(w.message) LIKE ? ESCAPE '!')
            ORDER BY CASE WHEN LOWER(w.title)=? OR LOWER(l.title)=? THEN 0 WHEN l.id IS NOT NULL THEN 1 ELSE 2 END,
              w.updated_at DESC,w.id DESC LIMIT 20 OFFSET ?
            """,(rs,n)->new Match(rs.getString("id"),rs.getLong("version"),new WorkSearchEvidence.Material(
                rs.getString("title"),rs.getString("story"),rs.getString("message"),rs.getString("lyric_title"),
                rs.getString("lyrics"),rs.getString("revision_id"),rs.getInt("lyric_number")).literal(keyword)),
            pattern,pattern,exact,pattern,owner,pattern,pattern,pattern,exact,exact,Math.multiplyExact(page,20));
        // Revalidate ownership after search, before exposing a projection.
        var owned=new ArrayList<WorkApi.Work>();
        var evidence=new HashMap<String,WorkSearchEvidence>();
        for(var match:matches)try{
            var work=works.get(owner,match.id());
            if(work.version()==match.version()){owned.add(work);evidence.put(work.id(),match.evidence());}
        }catch(com.qingfeng.music.common.exception.DomainException ignored){}
        return project(owner,owned,evidence);
    }
    private record Match(String id,long version,WorkSearchEvidence evidence){}
    private List<Item> project(String owner,List<WorkApi.Work> owned,Map<String,WorkSearchEvidence> evidence){
        if(owned.isEmpty())return List.of();
        String placeholders=String.join(",",Collections.nCopies(owned.size(),"?"));
        String sql="""
            SELECT w.id,
              (SELECT v.id FROM music_video_project v WHERE v.work_id=w.id AND v.creator_id=w.owner_id AND v.state IN ('READY','QUEUED','RUNNING','ASSEMBLING','UNCERTAIN') ORDER BY CASE WHEN v.state='READY' THEN 0 ELSE 1 END,v.created_at DESC,v.id DESC LIMIT 1) AS video_id,
              (SELECT v.state FROM music_video_project v WHERE v.work_id=w.id AND v.creator_id=w.owner_id AND v.state IN ('READY','QUEUED','RUNNING','ASSEMBLING','UNCERTAIN') ORDER BY CASE WHEN v.state='READY' THEN 0 ELSE 1 END,v.created_at DESC,v.id DESC LIMIT 1) AS video_state,

              (SELECT CASE WHEN q.error_code='LYRICS_SUBMISSION_UNKNOWN' THEN 'FAILED' ELSE q.status END FROM quick_creation q WHERE q.work_id=w.id ORDER BY q.created_at DESC,q.id DESC LIMIT 1) AS quick_status,
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
            byId.put(row.getString("id"),new State(status,row.getString("audio_id"),row.getString("audio_origin"),row.getString("song_title"),row.getString("cover_id"),row.getString("video_id"),row.getString("video_state")));
        },args.toArray());
        return owned.stream().filter(work->byId.containsKey(work.id())).map(work->{var s=byId.get(work.id());return new Item(work,s.status,s.audioId,s.origin,s.title,s.coverId,s.videoId,s.videoState,evidence.get(work.id()));}).toList();
    }
    private record State(String status,String audioId,String origin,String title,String coverId,String videoId,String videoState){}
}
