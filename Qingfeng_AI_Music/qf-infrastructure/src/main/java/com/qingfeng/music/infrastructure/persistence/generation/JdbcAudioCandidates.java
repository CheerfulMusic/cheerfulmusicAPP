package com.qingfeng.music.infrastructure.persistence.generation;
import com.qingfeng.music.generation.application.AudioCandidates;
import com.qingfeng.music.generation.application.port.MusicProvider.AudioInfo;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

public final class JdbcAudioCandidates implements AudioCandidates {
    private final JdbcTemplate jdbc;private final ObjectMapper json=new ObjectMapper();
    public JdbcAudioCandidates(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Candidate> get(String task){return jdbc.query("SELECT candidates_json FROM generation_audio_candidates WHERE task_id=?",(r,n)->List.of(json.readValue(r.getString(1),Candidate[].class)),task).stream().findFirst().orElse(List.of());}
    public void remember(String task,List<AudioInfo> tracks){
        if(tracks==null||tracks.isEmpty())return;
        if(tracks.size()>8)throw new IllegalArgumentException("候选音频数量异常");
        var current=get(task);var next=new ArrayList<Candidate>();
        if(current.isEmpty()){
            var ids=new HashSet<String>();
            for(var audio:tracks)if(ids.add(audio.audioId()))next.add(new Candidate(next.isEmpty()?"primary":UUID.nameUUIDFromBytes(audio.audioId().getBytes(StandardCharsets.UTF_8)).toString(),audio));
        }else{
            // 更新临时链接时按音频ID匹配，不因供应商返回顺序改变而调换A/B。
            for(var item:current)next.add(new Candidate(item.key(),tracks.stream().filter(a->a.audioId().equals(item.audio().audioId())).findFirst().orElse(item.audio())));
        }
        jdbc.update("INSERT INTO generation_audio_candidates(task_id,candidates_json) VALUES(?,?) ON DUPLICATE KEY UPDATE candidates_json=VALUES(candidates_json)",task,json.writeValueAsString(next));
    }
}
