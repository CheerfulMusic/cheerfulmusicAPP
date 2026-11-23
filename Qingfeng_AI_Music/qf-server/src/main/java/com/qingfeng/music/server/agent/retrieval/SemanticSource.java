package com.qingfeng.music.server.agent.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import com.qingfeng.music.server.query.WorkSearchEvidence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Authoritative private corpus. Never dereference text supplied by the vector database. */
public final class SemanticSource {
    public record Document(String owner,String work,long version,String revision,String title,String text,String hash,WorkSearchEvidence.Material material) {
        public Document(String owner,String work,long version,String revision,String title,String text,String hash){
            this(owner,work,version,revision,title,text,hash,new WorkSearchEvidence.Material(title,text,"","","",revision,0));
        }
    }
    private final JdbcTemplate jdbc;
    public SemanticSource(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Document> load(String owner,String work) {
        var rows=jdbc.queryForList("""
            SELECT w.title,w.story,w.message,w.version,l.id AS revision_id,l.title AS lyric_title,l.lyrics,
              (SELECT COUNT(*) FROM lyric_revision v WHERE v.work_id=l.work_id
               AND (v.created_at<l.created_at OR (v.created_at=l.created_at AND v.id<=l.id))) AS lyric_number
            FROM music_work w LEFT JOIN lyric_revision l ON l.id=(
              SELECT r.id FROM lyric_revision r WHERE r.work_id=w.id AND r.work_version=w.version AND r.confirmed=TRUE
              ORDER BY r.created_at DESC,r.id DESC LIMIT 1)
            WHERE w.id=? AND w.owner_id=? AND w.deleted_at IS NULL
            """,work,owner);
        if(rows.isEmpty())return Optional.empty();
        var row=rows.get(0);long version=((Number)row.get("version")).longValue();
        String revision=Objects.toString(row.get("revision_id"),"");
        String title=String.valueOf(row.get("title"));
        if((title.isBlank()||"智能创作".equals(title))&&!revision.isEmpty())title=String.valueOf(row.get("lyric_title"));
        String material=title+"\n"+row.get("story")+"\n"+row.get("message")+(revision.isEmpty()?"":"\n"+row.get("lyrics"));
        String hash=digest(owner+"\n"+work+"\n"+version+"\n"+revision+"\n"+material);
        var fields=new WorkSearchEvidence.Material(title,Objects.toString(row.get("story"),""),Objects.toString(row.get("message"),""),
            Objects.toString(row.get("lyric_title"),""),Objects.toString(row.get("lyrics"),""),revision,((Number)row.get("lyric_number")).intValue());
        return Optional.of(new Document(owner,work,version,revision,title,material,hash,fields));
    }
    public static String digest(String text){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
