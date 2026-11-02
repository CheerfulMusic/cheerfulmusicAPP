package com.qingfeng.music.infrastructure.persistence.lyrics;
import com.qingfeng.music.lyrics.application.CompositionRequests;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
public final class JdbcCompositionRequests implements CompositionRequests {
    private final JdbcTemplate jdbc;
    public JdbcCompositionRequests(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Entry> find(String work,String key){return jdbc.query("SELECT status,revision_id FROM lyrics_composition_request WHERE work_id=? AND request_key=?",(row,n)->new Entry(row.getString(1),row.getString(2)),work,key).stream().findFirst();}
    public boolean claim(String work,String key){try{jdbc.update("INSERT INTO lyrics_composition_request(work_id,request_key,status,created_at) VALUES(?,?,'PENDING',CURRENT_TIMESTAMP)",work,key);return true;}catch(DuplicateKeyException duplicate){return false;}}
    public void complete(String work,String key,String revision){jdbc.update("UPDATE lyrics_composition_request SET status='SUCCEEDED',revision_id=? WHERE work_id=? AND request_key=?",revision,work,key);}
    public void failed(String work,String key){jdbc.update("UPDATE lyrics_composition_request SET status='UNKNOWN' WHERE work_id=? AND request_key=? AND status='PENDING'",work,key);}
}
