package com.qingfeng.music.server.agent.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Authoritative private corpus. Never dereference text supplied by the vector database. */
public final class SemanticSource {
    public record Document(String owner,String work,long version,String revision,String title,String text,String hash) {}
    private final JdbcTemplate jdbc;
    public SemanticSource(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Document> load(String owner,String work) {
        var rows=jdbc.queryForList("SELECT id,title,story,message,version FROM music_work WHERE id=? AND owner_id=? AND deleted_at IS NULL",work,owner);
        if(rows.isEmpty())return Optional.empty();
        var row=rows.get(0);long version=((Number)row.get("version")).longValue();
        var lyrics=jdbc.queryForList("SELECT id,title,lyrics FROM lyric_revision WHERE work_id=? AND work_version=? AND confirmed=TRUE ORDER BY created_at DESC,id DESC LIMIT 1",work,version);
        String revision=lyrics.isEmpty()?"":String.valueOf(lyrics.get(0).get("id"));
        String title=String.valueOf(row.get("title"));
        if((title.isBlank()||"智能创作".equals(title))&&!lyrics.isEmpty())title=String.valueOf(lyrics.get(0).get("title"));
        String material=title+"\n"+row.get("story")+"\n"+row.get("message")+(lyrics.isEmpty()?"":"\n"+lyrics.get(0).get("lyrics"));
        String hash=digest(owner+"\n"+work+"\n"+version+"\n"+revision+"\n"+material);
        return Optional.of(new Document(owner,work,version,revision,title,material,hash));
    }
    public static String digest(String text){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
