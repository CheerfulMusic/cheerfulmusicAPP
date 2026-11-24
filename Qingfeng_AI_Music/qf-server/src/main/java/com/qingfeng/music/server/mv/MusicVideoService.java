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
    public record Shot(String prompt,int seconds,String imageId,int imageSeconds){public Shot(String prompt,int seconds){this(prompt,seconds,null,0);}}
    public record Plan(String title,String ratio,String resolution,long durationMillis,List<Shot> shots){}
    public record Quote(long durationMillis,int seconds,int credits,String resolution){}
    public static int rate(String resolution){return switch(resolution==null?"":resolution){case "480P"->1;case "720P"->2;case "1080P"->3;default->throw invalid("请选择 480P、720P 或 1080P 分辨率。");};}
    public Quote quote(String owner,String work,String asset,String resolution){
        int rate=rate(resolution);works.get(owner,work);media.get(owner,work,asset);
        long millis=duration.millis(owner,work,asset);
        if(millis<2000||millis>3_600_000)throw invalid("MV 支持 2 秒至 60 分钟的完整歌曲。");
        int seconds=Math.toIntExact((millis+999)/1000);return new Quote(millis,seconds,Math.multiplyExact(seconds,rate),resolution);
    }
    Optional<MediaApi.Asset> previousAudio(String owner,String work,String asset){
        works.get(owner,work);
        var selected=media.get(owner,work,asset);
        if(selected.versionNumber()==null||selected.versionNumber()<2)return Optional.empty();
        int previous=selected.versionNumber()-1;
        return media.list(owner,work).stream().filter(a->Objects.equals(a.versionNumber(),previous)).findFirst();
    }
    long availableCredits(String owner){return credits.balance(owner);}
    public Plan prepare(String owner,String work,String asset,Plan requested){
        validate(requested);var plan=fit(requested,quote(owner,work,asset,requested.resolution()));
        for(var shot:plan.shots())if(shot.seconds()>shot.imageSeconds()&&shot.prompt().isBlank())throw invalid("请填写每段视频提示词，或使用 AI 一键分镜。");
        for(var shot:plan.shots())if(shot.imageSeconds()>0){if(images==null)throw invalid("图片服务不可用。");images.resolve(owner,work,shot.imageId());}
        return plan;
    }
    static Plan fit(Plan requested,Quote quote){
        int count=(quote.seconds()+29)/30;
        if(requested.shots().stream().anyMatch(x->x.imageSeconds()>0)&&requested.shots().size()!=count)throw invalid("请按每 30 秒一段设置图片，最后一段按歌曲剩余时长。");
        var shots=new ArrayList<Shot>();
        for(int i=0;i<count;i++){
            var original=requested.shots().get(i*requested.shots().size()/count);int seconds=Math.min(30,quote.seconds()-i*30);
            if(original.imageSeconds()<0||original.imageSeconds()>seconds||original.imageSeconds()>0&&(original.imageId()==null||original.imageId().isBlank()))throw invalid("图片停留时间不能超过本段时长，且必须先上传图片。");
            shots.add(new Shot(original.prompt(),seconds,original.imageId(),original.imageSeconds()));
        }
        return new Plan(requested.title(),requested.ratio(),quote.resolution(),quote.durationMillis(),List.copyOf(shots));
    }
    public static int generatedSeconds(Plan plan){return plan.shots().stream().mapToInt(x->x.seconds()-x.imageSeconds()).sum();}
    public static int price(Plan plan){return Math.multiplyExact(generatedSeconds(plan),rate(plan.resolution()));}
    private final JdbcTemplate db;private final TransactionTemplate tx;private final WorkApi works;private final MediaApi media;
    private final VideoProvider provider;private final CreditsLedger credits;private final AudioDuration duration;private final VideoImages images;private final ObjectMapper json=new ObjectMapper();
    public MusicVideoService(JdbcTemplate db,TransactionTemplate tx,WorkApi works,MediaApi media,VideoProvider provider,CreditsLedger credits,AudioDuration duration){this(db,tx,works,media,provider,credits,duration,null);}
    public MusicVideoService(JdbcTemplate db,TransactionTemplate tx,WorkApi works,MediaApi media,VideoProvider provider,CreditsLedger credits,AudioDuration duration,VideoImages images){this.db=db;this.tx=tx;this.works=works;this.media=media;this.provider=provider;this.credits=credits;this.duration=duration;this.images=images;}
    public boolean available(){return provider!=null&&provider.available();}
    public static void validate(Plan plan){
        if(plan==null||plan.title()==null||plan.title().isBlank()||plan.title().length()>100||plan.ratio()==null||!Set.of("16:9","9:16","1:1").contains(plan.ratio())||plan.shots()==null||plan.shots().isEmpty()||plan.shots().size()>120)throw invalid("需要标题、画幅和剧情分镜。");
        rate(plan.resolution());
        for(var shot:plan.shots())if(shot==null||shot.prompt()==null||shot.prompt().length()>2000)throw invalid("每个分镜需要 1–2000 字画面描述；时长由系统按完整歌曲自动分配。");
    }
    public String create(String owner,String work,String asset,String key,Plan plan){
        validate(plan);var verified=prepare(owner,work,asset,plan);
        if(!verified.equals(plan))throw new DomainException("VIDEO_QUOTE_CHANGED","歌曲时长或分镜已变化，请重新确认完整 MV 的费用。");
        int price=price(plan);if(key==null||key.isBlank()||key.length()>100)throw invalid("缺少有效请求标识。");works.get(owner,work);media.get(owner,work,asset);
        String body=json.writeValueAsString(plan),fingerprint=hash(work+":"+asset+":"+body);
        return tx.execute(status->{
            db.queryForObject("SELECT id FROM agent_admission_lock WHERE id=1 FOR UPDATE",Integer.class);
            var existing=db.queryForList("SELECT id,fingerprint FROM music_video_project WHERE creator_id=? AND request_key=?",owner,key);
            if(!existing.isEmpty()){if(!fingerprint.equals(existing.get(0).get("fingerprint")))throw invalid("同一请求标识不能用于不同的 MV。");return (String)existing.get(0).get("id");}
            if(db.queryForObject("SELECT COUNT(*) FROM music_video_project WHERE creator_id=? AND work_id=? AND asset_id=? AND state IN ('QUEUED','RUNNING','ASSEMBLING','UNCERTAIN')",Long.class,owner,work,asset)>0)
                throw new DomainException("VIDEO_ALREADY_ACTIVE","这版歌曲已有 MV 正在排队、制作或等待核对，请查看原任务进度，不要重复提交。");
            if(!available())throw new DomainException("VIDEO_NOT_CONFIGURED","视频服务尚未配置模型、密钥和计费，暂未提交或扣费。");
            if(db.queryForObject("SELECT COUNT(*) FROM music_video_project WHERE creator_id=? AND state IN ('QUEUED','RUNNING','ASSEMBLING','UNCERTAIN')",Long.class,owner)>=2)throw invalid("已有 MV 正在制作或待核对，请先查看进度。");
            String id=UUID.randomUUID().toString();if(price>0)credits.debit(owner,price,"music-video:"+id);
            db.update("INSERT INTO music_video_project(id,creator_id,work_id,asset_id,request_key,fingerprint,state,plan_json) VALUES(?,?,?,?,?,?,'QUEUED',?)",id,owner,work,asset,key,fingerprint,body);
            for(int i=0;i<plan.shots().size();i++){var shot=plan.shots().get(i);db.update("INSERT INTO music_video_shot(id,project_id,shot_index,prompt,seconds,state) VALUES(?,?,?,?,?,'QUEUED')",UUID.randomUUID().toString(),id,i,shot.prompt(),shot.seconds());}
            return id;
        });
    }
    public Map<String,Object> get(String owner,String id){var rows=db.queryForList("SELECT id,work_id,asset_id,state,plan_json,error_code,created_at,updated_at FROM music_video_project WHERE id=? AND creator_id=?",id,owner);if(rows.isEmpty())throw new DomainException("VIDEO_NOT_FOUND","找不到该 MV。");works.get(owner,(String)rows.get(0).get("work_id"));var result=new LinkedHashMap<>(rows.get(0));result.put("shots",db.queryForList("SELECT shot_index,prompt,seconds,state,error_code FROM music_video_shot WHERE project_id=? ORDER BY shot_index",id));return result;}
    public List<Map<String,Object>> list(String owner,String work){works.get(owner,work);return db.queryForList("SELECT id,asset_id,request_key,state,created_at,error_code FROM music_video_project WHERE creator_id=? AND work_id=? ORDER BY CASE WHEN state IN ('QUEUED','RUNNING','ASSEMBLING','UNCERTAIN') THEN 0 ELSE 1 END,created_at DESC LIMIT 20",owner,work);}
    public void cancel(String owner,String id){
        get(owner,id);
        tx.executeWithoutResult(status->{
            db.queryForObject("SELECT id FROM agent_admission_lock WHERE id=1 FOR UPDATE",Integer.class);
            // QUEUED is refundable only before the first provider attempt. Retained or
            // previously submitted shots cannot become free through a recovery queue.
            if(db.queryForObject("SELECT COUNT(*) FROM music_video_shot WHERE project_id=? AND (state<>'QUEUED' OR provider_task_id IS NOT NULL)",Long.class,id)>0)
                throw invalid("任务已提交，不能承诺取消或退款。请查询原任务状态，不要重复提交。");
            int changed=db.update("UPDATE music_video_project SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND creator_id=? AND state='QUEUED'",id,owner);
            if(changed!=1)throw invalid("任务已提交，不能承诺取消或退款。请查询原任务状态，不要重复提交。");
            String planJson=db.queryForObject("SELECT plan_json FROM music_video_project WHERE id=?",String.class,id);
            if(price(json.readValue(planJson,Plan.class))>0)credits.refund(owner,"music-video:"+id);
            db.update("UPDATE music_video_shot SET state='CANCELLED' WHERE project_id=?",id);
        });
    }
    public void retryShot(String owner,String id,int index,String prompt){
        get(owner,id);
        if(prompt==null||prompt.isBlank()||prompt.length()>2000)throw invalid("请填写 1–2000 字的新画面描述。");
        tx.executeWithoutResult(status->{
            db.queryForObject("SELECT id FROM agent_admission_lock WHERE id=1 FOR UPDATE",Integer.class);
            var row=get(owner,id);
            if(!"FAILED".equals(row.get("state")))throw invalid("仅明确失败的镜头可以重试，请先刷新任务状态。");
            if(db.queryForObject("SELECT COUNT(*) FROM music_video_project WHERE creator_id=? AND work_id=? AND asset_id=? AND state IN ('QUEUED','RUNNING','ASSEMBLING','UNCERTAIN')",Long.class,owner,row.get("work_id"),row.get("asset_id"))>0)throw invalid("同一歌曲版本已有任务运行，请先查看进度。");
            if(db.queryForObject("SELECT COUNT(*) FROM music_video_project WHERE creator_id=? AND state IN ('QUEUED','RUNNING','ASSEMBLING','UNCERTAIN')",Long.class,owner)>=2)throw invalid("已有 MV 正在制作，请稍后重试。");
            if(!available())throw invalid("视频服务暂不可用。");
            var failed=db.queryForList("SELECT prompt FROM music_video_shot WHERE project_id=? AND shot_index=? AND state='FAILED'",id,index);
            if(failed.isEmpty()||prompt.trim().equals(failed.get(0).get("prompt")))throw invalid("请修改失败镜头的画面描述后重试。");
            db.update("UPDATE music_video_shot SET prompt=?,state='QUEUED',provider_task_id=NULL,error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE project_id=? AND shot_index=? AND state='FAILED'",prompt.trim(),id,index);
            // A retry continues the original paid project, even when every failed
            // attempt lacked a task ID. Never restore the fresh refundable state.
            db.update("UPDATE music_video_project SET state='RUNNING',error_code=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        });
    }
    public void retryAssembly(String owner,String id){get(owner,id);tx.executeWithoutResult(status->{db.queryForObject("SELECT id FROM agent_admission_lock WHERE id=1 FOR UPDATE",Integer.class);if(db.queryForObject("SELECT COUNT(*) FROM music_video_shot WHERE project_id=? AND state<>'READY'",Long.class,id)!=0)throw invalid("镜头尚未全部完成，不能重新合成。");if(db.update("UPDATE music_video_project SET state='ASSEMBLING',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND creator_id=? AND state='ASSEMBLY_FAILED'",id,owner)!=1)throw invalid("仅合成失败的任务可以重新合成。");});}
    private static DomainException invalid(String message){return new DomainException("INVALID_VIDEO_REQUEST",message);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
