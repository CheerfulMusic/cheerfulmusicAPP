package com.qingfeng.music.infrastructure.persistence.lyrics;

import com.qingfeng.music.lyrics.playback.*;
import com.qingfeng.music.usage.UsageBudget;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

public final class JdbcPlaybackLyricsRepository implements PlaybackLyricsRepository {
    private final JdbcTemplate jdbc;private final TransactionTemplate transactions;private final UsageBudget budget;
    private final ObjectMapper json=new ObjectMapper();
    public JdbcPlaybackLyricsRepository(JdbcTemplate jdbc,TransactionTemplate transactions,UsageBudget budget){this.jdbc=jdbc;this.transactions=transactions;this.budget=budget;}
    public Optional<Source> source(String work,String asset){
        return jdbc.query("SELECT m.id,m.work_id,g.provider,g.provider_task_id,g.audio_url,g.lyrics FROM media_asset m LEFT JOIN generation_task g ON g.id=m.generation_task_id WHERE m.work_id=? AND m.id=?",(r,n)->new Source(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)),work,asset).stream().findFirst();
    }
    public Optional<Stored> find(String asset){return jdbc.query("SELECT state,words_json,request_key,updated_at FROM media_lyrics_timeline WHERE asset_id=?",(r,n)->new Stored(r.getString(1),decode(r.getString(2)),r.getString(3),r.getTimestamp(4).toInstant()),asset).stream().findFirst();}
    private List<PlaybackLyrics.Word> decode(String raw){return raw==null?List.of():List.of(json.readValue(raw,PlaybackLyrics.Word[].class));}
    public boolean claim(String owner,Source source,String key){
        jdbc.update("INSERT INTO media_lyrics_timeline(asset_id,work_id,state,updated_at) VALUES(?,?,'NOT_REQUESTED',CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE asset_id=asset_id",source.assetId(),source.workId());
        return Boolean.TRUE.equals(transactions.execute(tx->{
            jdbc.queryForObject("SELECT asset_id FROM media_lyrics_timeline WHERE asset_id=? FOR UPDATE",String.class,source.assetId());
            var existing=find(source.assetId()).orElseThrow();
            if(!Set.of("NOT_REQUESTED","FAILED").contains(existing.status())||key.equals(existing.requestKey()))return false;
            String reservation=UUID.nameUUIDFromBytes((source.assetId()+":"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            budget.reserve(owner,source.workId(),reservation,UsageBudget.Kind.TIMING);
            jdbc.update("UPDATE media_lyrics_timeline SET state='FETCHING',request_key=?,words_json=NULL,attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE asset_id=?",key,source.assetId());return true;
        }));
    }
    public void finish(String asset,String key,String state,List<PlaybackLyrics.Word> words){jdbc.update("UPDATE media_lyrics_timeline SET state=?,words_json=?,updated_at=CURRENT_TIMESTAMP WHERE asset_id=? AND request_key=? AND state IN ('FETCHING','UNKNOWN')",state,json.writeValueAsString(words),asset,key);}
    public void expire(String asset,Instant before){jdbc.update("UPDATE media_lyrics_timeline SET state='UNKNOWN',updated_at=CURRENT_TIMESTAMP WHERE asset_id=? AND state='FETCHING' AND updated_at<?",asset,Timestamp.from(before));}
}
