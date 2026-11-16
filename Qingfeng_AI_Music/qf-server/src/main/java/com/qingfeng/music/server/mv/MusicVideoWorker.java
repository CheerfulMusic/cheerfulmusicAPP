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
    private final JdbcTemplate db;private final TransactionTemplate tx;private final VideoProvider provider;private final MediaApi media;private final Path root;private final String ffmpeg;private final ObjectMapper json=new ObjectMapper();
    public MusicVideoWorker(JdbcTemplate db,TransactionTemplate tx,VideoProvider provider,MediaApi media,Path root,String ffmpeg){this.db=db;this.tx=tx;this.provider=provider;this.media=media;this.root=root.toAbsolutePath().normalize();this.ffmpeg=ffmpeg;}
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
                    shotState(shotId,"SUBMITTING",null);
                    try{String task=provider.submit((String)shot.get("prompt"),((Number)shot.get("seconds")).intValue(),plan.ratio(),shotId);db.update("UPDATE music_video_shot SET provider_task_id=?,state='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=?",task,shotId);}
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
        var args=new ArrayList<String>(List.of(ffmpeg,"-nostdin","-y","-loglevel","error"));
        for(var shot:shots){args.add("-i");args.add(folder.resolve(shot.get("id")+".mp4").toString());}
        args.addAll(List.of("-i",song.toString()));
        var plan=json.readValue((String)project.get("plan_json"),MusicVideoService.Plan.class);String size=switch(plan.ratio()){case "9:16"->"720:1280";case "1:1"->"720:720";default->"1280:720";};
        var filters=new StringBuilder();int total=0;
        for(int i=0;i<shots.size();i++){int seconds=((Number)shots.get(i).get("seconds")).intValue();total+=seconds;filters.append("[").append(i).append(":v]scale=").append(size).append(":force_original_aspect_ratio=decrease,pad=").append(size).append(":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=25,trim=duration=").append(seconds).append(",setpts=PTS-STARTPTS[v").append(i).append("];");}
        for(int i=0;i<shots.size();i++)filters.append("[v").append(i).append("]");
        filters.append("concat=n=").append(shots.size()).append(":v=1:a=0[out]");
        Path result=folder.resolve("result.tmp.mp4");args.addAll(List.of("-filter_complex",filters.toString(),"-map","[out]","-map",shots.size()+":a:0","-c:v","libx264","-preset","veryfast","-crf","22","-c:a","aac","-t",Integer.toString(total),"-shortest","-movflags","+faststart",result.toString()));
        var process=new ProcessBuilder(args).redirectError(folder.resolve("assembly.log").toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try { if(!process.waitFor(300,TimeUnit.SECONDS))throw new IllegalStateException("Video assembly timeout"); } finally { if(process.isAlive())process.destroyForcibly(); }
        if(process.exitValue()!=0||!Files.isRegularFile(result)||Files.size(result)<100)throw new IllegalStateException("Video assembly failed");
        Files.move(result,folder.resolve("result.mp4"),StandardCopyOption.REPLACE_EXISTING);
    }
}
