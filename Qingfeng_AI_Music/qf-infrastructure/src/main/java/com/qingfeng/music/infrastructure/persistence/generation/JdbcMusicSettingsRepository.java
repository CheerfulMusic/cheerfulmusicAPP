package com.qingfeng.music.infrastructure.persistence.generation;
import com.qingfeng.music.settings.MusicSettings.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

public final class JdbcMusicSettingsRepository implements Repository {
 private final JdbcTemplate jdbc;private final ObjectMapper json=new ObjectMapper();
 public JdbcMusicSettingsRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public long lockVersion(String work){return jdbc.queryForObject("SELECT version FROM music_work WHERE id=? FOR UPDATE",Long.class,work);}
 public Optional<Saved> find(String work){return jdbc.query("SELECT settings_json FROM work_music_preferences WHERE work_id=?",(r,n)->json.readValue(r.getString(1),Saved.class),work).stream().findFirst();}
 public void save(String work,Saved settings){jdbc.update("INSERT INTO work_music_preferences(work_id,settings_json) VALUES(?,?) ON DUPLICATE KEY UPDATE settings_json=VALUES(settings_json)",work,json.writeValueAsString(settings));}
 public void snapshot(String task,Direction settings){jdbc.update("INSERT INTO generation_music_preferences(task_id,snapshot_json) VALUES(?,?) ON DUPLICATE KEY UPDATE task_id=task_id",task,json.writeValueAsString(settings));}
 public Optional<Direction> task(String work,String task){return jdbc.query("SELECT s.snapshot_json FROM generation_music_preferences s JOIN generation_task g ON g.id=s.task_id WHERE s.task_id=? AND g.work_id=?",(r,n)->json.readValue(r.getString(1),Direction.class),task,work).stream().findFirst();}
}
