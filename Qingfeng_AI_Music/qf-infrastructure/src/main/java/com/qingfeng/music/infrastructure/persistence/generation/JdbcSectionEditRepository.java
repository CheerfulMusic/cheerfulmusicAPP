package com.qingfeng.music.infrastructure.persistence.generation;

import com.qingfeng.music.generation.application.SectionEditRepository;
import com.qingfeng.music.generation.application.port.MusicProvider;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcSectionEditRepository implements SectionEditRepository {
    private final JdbcTemplate jdbc;
    public JdbcSectionEditRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Source> source(String work,String asset){return jdbc.query("""
        SELECT g.id,g.provider,g.provider_task_id,g.audio_url,s.audio_id,s.duration_s,g.title,g.lyrics,g.style,g.vocal_gender
        FROM media_asset a JOIN generation_task g ON g.id=a.generation_task_id AND g.asset_id=a.id
        LEFT JOIN generation_audio_source s ON s.task_id=g.id
        WHERE a.work_id=? AND a.id=? AND a.origin='GENERATED' AND g.status='SUCCEEDED'
        """,(r,n)->new Source(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),(Double)r.getObject(6,Double.class),r.getString(7),r.getString(8),r.getString(9),r.getString(10)),work,asset).stream().findFirst();}
    public Optional<Edit> edit(String task){return jdbc.query("SELECT * FROM section_edit WHERE task_id=?",(r,n)->new Edit(r.getString("task_id"),r.getString("source_asset_id"),r.getString("source_provider_task_id"),r.getString("source_provider_audio_id"),r.getDouble("start_s"),r.getDouble("end_s"),r.getDouble("source_duration_s"),r.getInt("text_start"),r.getInt("text_end"),r.getString("replacement_lyrics"),r.getString("request_hash")),task).stream().findFirst();}
    public void insert(Edit e){jdbc.update("INSERT INTO section_edit(task_id,source_asset_id,source_provider_task_id,source_provider_audio_id,start_s,end_s,source_duration_s,text_start,text_end,replacement_lyrics,request_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?)",e.taskId(),e.sourceAssetId(),e.sourceProviderTaskId(),e.sourceProviderAudioId(),e.start(),e.end(),e.sourceDuration(),e.textStart(),e.textEnd(),e.replacementLyrics(),e.requestHash());}
    public void recordAudio(String task,MusicProvider.AudioInfo info){
        if(info==null||info.audioId()==null||!info.audioId().matches("[A-Za-z0-9_-]{1,255}")||!Double.isFinite(info.duration())||info.duration()<=0||info.duration()>86400||info.audioUrl()==null||info.audioUrl().length()>4096)return;
        jdbc.update("INSERT INTO generation_audio_source(task_id,audio_id,duration_s,audio_url) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE audio_id=VALUES(audio_id),duration_s=VALUES(duration_s),audio_url=VALUES(audio_url)",task,info.audioId(),info.duration(),info.audioUrl());
    }
    public List<Item> list(String work){return jdbc.query("SELECT e.*,g.asset_id,g.status,g.last_error_code,g.title,g.created_at FROM section_edit e JOIN generation_task g ON g.id=e.task_id WHERE g.work_id=? ORDER BY g.created_at DESC,g.id DESC LIMIT 50",(r,n)->new Item(r.getString("task_id"),r.getString("source_asset_id"),r.getString("asset_id"),r.getDouble("start_s"),r.getDouble("end_s"),r.getString("replacement_lyrics"),r.getString("status"),r.getString("last_error_code"),r.getString("title"),r.getTimestamp("created_at").toInstant()),work);}
}
