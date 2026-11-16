package com.qingfeng.music.server.mv;

import com.qingfeng.music.media.api.MediaApi;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One bounded worker per instance; DB leases arbitrate instances and survive restarts. */
public final class MusicVideoWorker implements AutoCloseable {
    private final java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"music-video-worker");t.setDaemon(true);return t;});
    private final JdbcTemplate db;private final TransactionTemplate tx;private final VideoProvider provider;private final MediaApi media;private final Path root;private final String ffmpeg;private final VideoImages images;private final ObjectMapper json=new ObjectMapper();
    public MusicVideoWorker(JdbcTemplate db,TransactionTemplate tx,VideoProvider provider,MediaApi media,Path root,String ffmpeg){this(db,tx,provider,media,root,ffmpeg,null);}
    public MusicVideoWorker(JdbcTemplate db,TransactionTemplate tx,VideoProvider provider,MediaApi media,Path root,String ffmpeg,VideoImages images){this.db=db;this.tx=tx;this.provider=provider;this.media=media;this.root=root.toAbsolutePath().normalize();this.ffmpeg=ffmpeg;this.images=images;}
    @Scheduled(fixedDelayString="${qingfeng.video.poll-delay-ms:10000}")
    public void schedule(){if(busy.compareAndSet(false,true)){try{executor.execute(()->{try{tick();}finally{busy.set(false);}});}catch(java.util.concurrent.RejectedExecutionException e){busy.set(false);}}}
    @Override public void close(){executor.shutdownNow();}
    public void tick(){
        if(!provider.available())return;
        var project=tx.execute(status->{
            db.queryForObject("SELECT id FROM agent_admission_lock WHERE id=1 FOR UPDATE",Integer.class);
            var candidates=db.queryForList("SELECT * FROM music_video_project WHERE state IN ('QUEUED','RUNNING','ASSEMBLING') AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) ORDER BY updated_at,created_at LIMIT 1");
            if(candidates.isEmpty())return null;var row=candidates.get(0);
            db.update("UPDATE music_video_project SET state='RUNNING',lease_until=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",java.sql.Timestamp.from(Instant.now().plusSeconds(600)),row.get("id"));return row;
        });
        if(project==null)return;String id=(String)project.get("id");
        try{
            var folder=folder(id);Files.createDirectories(folder);
            var plan=json.readValue((String)project.get("plan_json"),MusicVideoService.Plan.class);
            var shots=db.queryForList("SELECT * FROM music_video_shot WHERE project_id=? ORDER BY shot_index",id);
            for(var shot:shots){
                String shotId=(String)shot.get("id"),state=(String)shot.get("state");
                if("SUBMITTING".equals(state)){shotState(shotId,"UNCERTAIN","VIDEO_SUBMIT_INTERRUPTED");projectState(id,"UNCERTAIN","VIDEO_SUBMIT_INTERRUPTED");return;}
                if("UNCERTAIN".equals(state)||"FAILED".equals(state)){projectState(id,state,"VIDEO_SHOT_"+state);return;}
                if("QUEUED".equals(state)){
                    var segment=plan.shots().get(((Number)shot.get("shot_index")).intValue());
                    int generated=segment.seconds()-segment.imageSeconds();
                    if(generated==0){shotState(shotId,"READY",null);return;}
                    shotState(shotId,"SUBMITTING",null);
                    try{String task=provider.submit((String)shot.get("prompt"),Math.max(2,generated),plan.ratio(),plan.resolution(),shotId);db.update("UPDATE music_video_shot SET provider_task_id=?,state='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=?",task,shotId);}
                    catch(VideoProvider.Failure e){shotState(shotId,e.uncertain?"UNCERTAIN":"FAILED",e.code);projectState(id,e.uncertain?"UNCERTAIN":"FAILED",e.code);}return;
                }
                if("RUNNING".equals(state)){
                    if (((java.sql.Timestamp)shot.get("updated_at")).toInstant().isBefore(Instant.now().minusSeconds(7200))) {
                        shotState(shotId,"UNCERTAIN","VIDEO_POLL_DEADLINE");projectState(id,"UNCERTAIN","VIDEO_POLL_DEADLINE");return;
                    }
                    String task=(String)shot.get("provider_task_id");String upstream=provider.status(task);
                    if(Set.of("failed","error","cancelled","canceled").contains(upstream)){shotState(shotId,"FAILED","VIDEO_PROVIDER_FAILED");projectState(id,"FAILED","VIDEO_PROVIDER_FAILED");return;}
                    if(Set.of("succeeded","success","completed").contains(upstream)){
                        Path temporary=folder.resolve(shotId+"-"+UUID.randomUUID()+".part"),saved=folder.resolve(shotId+".mp4");
                        try { provider.download(task,temporary);Files.move(temporary,saved,StandardCopyOption.REPLACE_EXISTING);shotState(shotId,"READY",null); } finally { Files.deleteIfExists(temporary); }
                    }return;
                }
            }
            projectState(id,"ASSEMBLING",null);assemble(project,shots,folder);projectState(id,"READY",null);
        }catch(VideoProvider.Failure error){db.update("UPDATE music_video_project SET error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",error.code,id);}
        catch(Exception error){projectState(id,"ASSEMBLY_FAILED","VIDEO_ASSEMBLY_FAILED");}
        finally{db.update("UPDATE music_video_project SET lease_until=NULL WHERE id=?",id);}
    }
    public Path folder(String id){if(id==null||!id.matches("[a-fA-F0-9-]{36}"))throw new IllegalArgumentException("Invalid video ID");return root.resolve(id);}
    private void shotState(String id,String state,String error){db.update("UPDATE music_video_shot SET state=?,error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",state,error,id);}
    private void projectState(String id,String state,String error){db.update("UPDATE music_video_project SET state=?,error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",state,error,id);}
    private void assemble(Map<String,Object> project,List<Map<String,Object>> shots,Path folder)throws Exception{
        String owner=(String)project.get("creator_id"),work=(String)project.get("work_id"),asset=(String)project.get("asset_id");
        var audio=media.open(owner,work,asset);if(audio.size()>100L*1024*1024)throw new IllegalArgumentException("Audio too large");
        Path song=folder.resolve("song.audio");try(var input=audio.source().open()){try(var output=Files.newOutputStream(song)){byte[] buffer=new byte[65536];long total=0;for(int n;(n=input.read(buffer))!=-1;){total+=n;if(total>100L*1024*1024)throw new IllegalArgumentException("Audio too large");output.write(buffer,0,n);}}}
        var plan=json.readValue((String)project.get("plan_json"),MusicVideoService.Plan.class);
        int height=switch(plan.resolution()){case "480P"->480;case "1080P"->1080;default->720;};
        int width=height==480?854:height*16/9;
        String size=switch(plan.ratio()){case "9:16"->height+":"+width;case "1:1"->height+":"+height;default->width+":"+height;};
        var concat=new StringBuilder();var parts=new ArrayList<String>();
        // Encode each video/still part separately to bound memory for full songs.
        for(int i=0;i<shots.size();i++){
            var shot=shots.get(i);var scene=plan.shots().get(i);int generated=scene.seconds()-scene.imageSeconds();
            String common="scale="+size+":force_original_aspect_ratio=decrease,pad="+size+":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=25";
            if(generated>0){
                String part="segment-"+i+"-video.mp4";
                String filter=common+",tpad=stop_mode=clone:stop_duration="+generated+",trim=duration="+generated+",setpts=PTS-STARTPTS";
                runAssembly(List.of(ffmpeg,"-nostdin","-y","-loglevel","error","-i",folder.resolve(shot.get("id")+".mp4").toString(),"-vf",filter,"-an","-c:v","libx264","-preset","veryfast","-crf","22","-pix_fmt","yuv420p",folder.resolve(part).toString()),folder,(String)project.get("id"));parts.add(part);
            }
            if(scene.imageSeconds()>0){
                if(images==null)throw new IllegalStateException("Image storage unavailable");
                String part="segment-"+i+"-image.mp4";
                runAssembly(List.of(ffmpeg,"-nostdin","-y","-loglevel","error","-loop","1","-i",images.resolve(owner,work,scene.imageId()).toString(),"-vf",common,"-t",Integer.toString(scene.imageSeconds()),"-an","-c:v","libx264","-preset","veryfast","-crf","22","-pix_fmt","yuv420p",folder.resolve(part).toString()),folder,(String)project.get("id"));parts.add(part);
            }
        }
        for(String part:parts)concat.append("file '").append(part).append("'\n");
        Path list=folder.resolve("segments.txt");Files.writeString(list,concat.toString());
        Path result=folder.resolve("result.tmp.mp4");
        runAssembly(List.of(ffmpeg,"-nostdin","-y","-loglevel","error","-f","concat","-safe","1","-i",list.toString(),"-i",song.toString(),"-map","0:v:0","-map","1:a:0","-c:v","copy","-c:a","aac","-t",java.math.BigDecimal.valueOf(plan.durationMillis(),3).toPlainString(),"-shortest","-movflags","+faststart",result.toString()),folder,(String)project.get("id"));
        if(!Files.isRegularFile(result)||Files.size(result)<1000)throw new IllegalStateException("Video assembly empty");
        Files.move(result,folder.resolve("result.mp4"),StandardCopyOption.REPLACE_EXISTING);
        for(String part:parts)Files.deleteIfExists(folder.resolve(part));
        Files.deleteIfExists(song);Files.deleteIfExists(list);

    }
    private void runAssembly(List<String> args,Path folder,String id)throws Exception{
        db.update("UPDATE music_video_project SET lease_until=? WHERE id=?",java.sql.Timestamp.from(Instant.now().plusSeconds(600)),id);
        var process=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.appendTo(folder.resolve("assembly.log").toFile())).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try{if(!process.waitFor(300,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("Video assembly failed");}
        finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
    }

}
