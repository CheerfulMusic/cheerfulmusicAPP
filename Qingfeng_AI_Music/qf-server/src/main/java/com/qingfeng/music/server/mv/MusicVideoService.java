package com.qingfeng.music.server.mv;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.credits.CreditsLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** The project and immutable shot plan are committed before any provider request. */
public final class MusicVideoService {
    public record Shot(String prompt,int seconds){}
    public record Plan(String title,String ratio,List<Shot> shots){}
    private final JdbcTemplate db;private final TransactionTemplate tx;private final WorkApi works;private final MediaApi media;
    private final VideoProvider provider;private final CreditsLedger credits;private final int price;private final ObjectMapper json=new ObjectMapper();
    public MusicVideoService(JdbcTemplate db,TransactionTemplate tx,WorkApi works,MediaApi media,VideoProvider provider,CreditsLedger credits,int price){this.db=db;this.tx=tx;this.works=works;this.media=media;this.provider=provider;this.credits=credits;this.price=price;}
    public int price(){return price;}
    public boolean available(){return provider.available()&&price>0;}
    public static void validate(Plan plan){
        if(plan==null||plan.title()==null||plan.title().isBlank()||plan.title().length()>100||(plan.ratio()==null||!Set.of("16:9","9:16","1:1").contains(plan.ratio()))||plan.shots()==null||plan.shots().isEmpty()||plan.shots().size()>12)throw invalid("需要标题、画幅及 1–12 个分镜。");
        int total=0;for(var shot:plan.shots()){if(shot==null||shot.prompt()==null||shot.prompt().isBlank()||shot.prompt().length()>2000||shot.seconds()<4||shot.seconds()>15)throw invalid("每个分镜需要 1–2000 字画面描述，时长 4–15 秒。");total+=shot.seconds();}if(total>120)throw invalid("单个 MV 最长 120 秒；可先制作歌曲片段。");
    }
    public String create(String owner,String work,String asset,String key,Plan plan){
        validate(plan);if(key==null||key.isBlank()||key.length()>100)throw invalid("缺少有效请求标识。");works.get(owner,work);media.get(owner,work,asset);
        String body=json.writeValueAsString(plan),fingerprint=hash(work+":"+asset+":"+body);
        return tx.execute(status->{
            db.queryForObject("SELECT id FROM agent_admission_lock WHERE id=1 FOR UPDATE",Integer.class);
            var existing=db.queryForList("SELECT id,fingerprint FROM music_video_project WHERE creator_id=? AND request_key=?",owner,key);
            if(!existing.isEmpty()){if(!fingerprint.equals(existing.get(0).get("fingerprint")))throw invalid("同一请求标识不能用于不同的 MV。");return (String)existing.get(0).get("id");}
            if(!available())throw new DomainException("VIDEO_NOT_CONFIGURED","视频服务尚未配置模型、密钥和计费，暂未提交或扣费。");
            if(db.queryForObject("SELECT COUNT(*) FROM music_video_project WHERE creator_id=? AND state IN ('QUEUED','RUNNING','ASSEMBLING','UNCERTAIN')",Long.class,owner)>=2)throw invalid("已有 MV 正在制作或待核对，请先查看进度。");
            String id=UUID.randomUUID().toString();credits.debit(owner,price,"music-video:"+id);
            db.update("INSERT INTO music_video_project(id,creator_id,work_id,asset_id,request_key,fingerprint,state,plan_json) VALUES(?,?,?,?,?,?,'QUEUED',?)",id,owner,work,asset,key,fingerprint,body);
            for(int i=0;i<plan.shots().size();i++){var shot=plan.shots().get(i);db.update("INSERT INTO music_video_shot(id,project_id,shot_index,prompt,seconds,state) VALUES(?,?,?,?,?,'QUEUED')",UUID.randomUUID().toString(),id,i,shot.prompt(),shot.seconds());}
            return id;
        });
    }
    public Map<String,Object> get(String owner,String id){var rows=db.queryForList("SELECT id,work_id,asset_id,state,plan_json,error_code,created_at,updated_at FROM music_video_project WHERE id=? AND creator_id=?",id,owner);if(rows.isEmpty())throw new DomainException("VIDEO_NOT_FOUND","找不到该 MV。");works.get(owner,(String)rows.get(0).get("work_id"));var result=new LinkedHashMap<>(rows.get(0));result.put("shots",db.queryForList("SELECT shot_index,seconds,state,error_code FROM music_video_shot WHERE project_id=? ORDER BY shot_index",id));return result;}
    public List<Map<String,Object>> list(String owner,String work){works.get(owner,work);return db.queryForList("SELECT id,state,created_at,error_code FROM music_video_project WHERE creator_id=? AND work_id=? ORDER BY created_at DESC LIMIT 20",owner,work);}
    public void cancel(String owner,String id){get(owner,id);tx.executeWithoutResult(status->{db.queryForObject("SELECT id FROM agent_admission_lock WHERE id=1 FOR UPDATE",Integer.class);int changed=db.update("UPDATE music_video_project SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND creator_id=? AND state='QUEUED'",id,owner);if(changed==1){credits.refund(owner,"music-video:"+id);db.update("UPDATE music_video_shot SET state='CANCELLED' WHERE project_id=?",id);}else throw invalid("任务已提交，不能承诺取消或退款。请查询原任务状态，不要重复提交。");});}
    public void retryAssembly(String owner,String id){get(owner,id);tx.executeWithoutResult(status->{db.queryForObject("SELECT id FROM agent_admission_lock WHERE id=1 FOR UPDATE",Integer.class);if(db.queryForObject("SELECT COUNT(*) FROM music_video_shot WHERE project_id=? AND state<>'READY'",Long.class,id)!=0)throw invalid("镜头尚未全部完成，不能重新合成。");if(db.update("UPDATE music_video_project SET state='ASSEMBLING',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND creator_id=? AND state='ASSEMBLY_FAILED'",id,owner)!=1)throw invalid("仅合成失败的任务可以重新合成。");});}
    private static DomainException invalid(String message){return new DomainException("INVALID_VIDEO_REQUEST",message);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
