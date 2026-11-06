package com.qingfeng.music.infrastructure.persistence.generation;
import com.qingfeng.music.settings.CreativePlan;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.Optional;

public final class JdbcCreativePlanRepository implements CreativePlan.Repository {
    private final JdbcTemplate jdbc;private final ObjectMapper json=new ObjectMapper();
    public JdbcCreativePlanRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<CreativePlan> find(String work,String hash){return jdbc.query(
        "SELECT plan_json FROM lyric_creative_plan WHERE work_id=? AND source_hash=? ORDER BY created_at DESC,revision_id DESC LIMIT 1",
        (r,n)->json.readValue(r.getString(1),CreativePlan.class),work,hash).stream().findFirst();}
    public void save(String work,String revision,String hash,CreativePlan plan){jdbc.update(
        "INSERT INTO lyric_creative_plan(revision_id,work_id,source_hash,plan_json,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP(6))",
        revision,work,hash,json.writeValueAsString(plan));}
}
