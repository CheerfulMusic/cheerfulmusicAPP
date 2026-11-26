package com.qingfeng.music.server.lyrics;

import com.qingfeng.music.lyrics.playback.*;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.server.hotsong.RecommendationSamples;
import com.qingfeng.music.usage.UsageBudget;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable, opt-in background preparation. Public playback never calls processOnce or enqueue. */
public final class LyricsPreparation {
    // A KIE timing fetch can make two 55s calls plus separate account-rate waits.
    private static final long CLAIM_SECONDS=240;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final PlaybackLyricsRepository timelines;
    private final LyricsTimingProvider original;
    private final AudioTranscriptionProvider transcription;
    private final ObjectMapper json=new ObjectMapper();
    private final int dailyLimit;
    private final boolean transcriptionFallback;
    private volatile Boolean schemaReady;
    public LyricsPreparation(JdbcTemplate db,TransactionTemplate tx,PlaybackLyricsRepository timelines,
            LyricsTimingProvider original,AudioTranscriptionProvider transcription,int dailyLimit) {
        this(db,tx,timelines,original,transcription,dailyLimit,true);
    }
    public LyricsPreparation(JdbcTemplate db,TransactionTemplate tx,PlaybackLyricsRepository timelines,
            LyricsTimingProvider original,AudioTranscriptionProvider transcription,int dailyLimit,boolean transcriptionFallback) {
        this.db=db;this.tx=tx;this.timelines=timelines;this.original=original;this.transcription=transcription;
        this.dailyLimit=Math.max(0,Math.min(dailyLimit,200));
        this.transcriptionFallback=transcriptionFallback;
    }
    public boolean schemaAvailable() {
        if(schemaReady==null) {
            schemaReady=Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>) connection->{
                var metadata=connection.getMetaData();
                for(String table:List.of("lyrics_preparation_job","lyrics_preparation_control")) {
                    try(var tables=metadata.getTables(connection.getCatalog(),connection.getSchema(),table,new String[]{"TABLE"})) {
                        if(!tables.next())return false;
                    }
                }
                return true;
            }));
        }
        return schemaReady;
    }
    public boolean enqueueInteractive(String owner,PlaybackLyricsRepository.Source source,String request,UsageBudget budget) {
        if(!schemaAvailable())return false;
        tx.executeWithoutResult(status->{
            // Usage admission locks the creator first. Automatic enqueue only takes the timing lock.
            db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);
            lock();
            var asset=source.assetId();
            var work=source.workId();
            if(!visible(work,asset))throw new DomainException("MEDIA_NOT_FOUND","音频不存在");
            var timeline=timelines.find(asset);
            if(timeline.isPresent()&&(!Set.of("NOT_REQUESTED","FAILED").contains(timeline.get().status())
                    || request.equals(timeline.get().requestKey())))return;
            var jobs=db.query("SELECT state FROM lyrics_preparation_job WHERE source_key=?",(row,n)->row.getString(1),key(asset));
            if(!jobs.isEmpty()&&!jobs.get(0).equals("FAILED"))return;
            var reservation=PlaybackLyricsRepository.reservationKey(asset,request);
            if(db.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='TIMING' AND work_id=? AND request_key=?",
                    Integer.class,work,reservation)>0)return;
            budget.reserve(owner,work,reservation,UsageBudget.Kind.TIMING);
            stageTimeline(asset,work,request,timeline.isPresent());
            if(jobs.isEmpty())insertJob(key(asset),asset,work,null,source.audioUrl(),source.providerTaskId(),request);
            else db.update("""
                    UPDATE lyrics_preparation_job SET state='PENDING',mode='ORIGINAL',request_key=?,
                        audio_url=?,original_task_id=?,external_task_id=NULL,poll_failures=0,result_json=NULL,
                        lease_token=NULL,lease_until=NULL,error_code=NULL,next_attempt_at=CURRENT_TIMESTAMP,
                        updated_at=CURRENT_TIMESTAMP WHERE source_key=? AND state='FAILED'
                    """,request,source.audioUrl(),source.providerTaskId(),key(asset));
        });
        return true;
    }
    private static String key(String asset) { return "asset:"+asset; }
    public static String sampleKey(String sample) {
        var song=RecommendationSamples.playbackSong(sample).orElseThrow();
        String object=RecommendationSamples.objectKey(song.audio().substring("/media/".length())).orElseThrow();
        return "sample:"+sample+":"+UUID.nameUUIDFromBytes(object.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    /** A bounded scan covers old public songs and new audio versions after successful archive. */
    public int enqueueArchived(int limit) { return enqueueArchived(limit,null); }
    public int enqueueNewAndPublished(int limit) {
        return enqueueNew(limit,true);
    }
    public int enqueueNew(int limit,boolean includePublishedHistory) {
        Instant since=tx.execute(status->{
            lock();
            var saved=db.query("SELECT started_at FROM lyrics_preparation_control WHERE control_key='new_audio_since'",(r,n)->r.getTimestamp(1).toInstant());
            if(!saved.isEmpty())return saved.get(0);
            Instant start=Instant.now().minusSeconds(60);
            db.update("INSERT INTO lyrics_preparation_control(control_key,started_at) VALUES('new_audio_since',?)",Timestamp.from(start));return start;
        });
        return enqueueArchived(limit,since,includePublishedHistory);
    }
    private int enqueueArchived(int limit,Instant since) {
        return enqueueArchived(limit,since,true);
    }
    private int enqueueArchived(int limit,Instant since,boolean includePublishedHistory) {
        boolean allowTranscription=transcriptionFallback&&transcription.isConfigured();
        if(!original.isConfigured()&&!allowTranscription)return 0;
        var rows=db.queryForList("""
                SELECT m.id,m.work_id,g.provider_task_id,COALESCE(m.source_audio_url,g.audio_url) AS audio
                FROM media_asset m JOIN music_work w ON w.id=m.work_id
                JOIN generation_task g ON g.id=m.generation_task_id
                LEFT JOIN media_asset_annotation a ON a.asset_id=m.id
                LEFT JOIN media_lyrics_timeline l ON l.asset_id=m.id
                LEFT JOIN lyrics_preparation_job j ON j.source_key=CONCAT('asset:',m.id)
                WHERE w.deleted_at IS NULL AND a.deleted_at IS NULL AND g.status='SUCCEEDED'
                  AND m.origin='GENERATED' AND COALESCE(m.source_audio_url,g.audio_url) IS NOT NULL
                  AND ((g.provider_task_id IS NOT NULL AND ?=TRUE) OR (g.provider_task_id IS NULL AND ?=TRUE))
                  AND j.source_key IS NULL AND (l.asset_id IS NULL OR l.state='NOT_REQUESTED')
                  AND (? IS NULL OR m.created_at>=? OR (?=TRUE AND EXISTS (
                    SELECT r.id FROM artist_song_release r JOIN artist_profile p ON p.creator_id=r.creator_id
                    WHERE r.asset_id=m.id AND r.show_on_profile=TRUE AND r.discover_published_at IS NOT NULL AND p.published=TRUE)))
                ORDER BY m.created_at,m.id LIMIT ?
                """,original.isConfigured(),allowTranscription,since==null?null:Timestamp.from(since),
                since==null?null:Timestamp.from(since),includePublishedHistory,Math.max(1,Math.min(limit,100)));
        int count=0;
        for(var row:rows) if(enqueue(key((String)row.get("id")),(String)row.get("id"),(String)row.get("work_id"),null,
                (String)row.get("audio"),(String)row.get("provider_task_id")))count++;
        return count;
    }
    /** Only the curated catalog is eligible. A temporary upload URL may be used for local backfill. */
    public boolean enqueueSample(String sample,String audioUrl) {
        sampleKey(sample);
        if(packagedSample(sample).isPresent())return false;
        return enqueue(sampleKey(sample),null,null,sample,audioUrl,null);
    }
    private boolean enqueue(String source,String asset,String work,String sample,String audio,String task) {
        return Boolean.TRUE.equals(tx.execute(status->{
            lock();
            if(!db.queryForList("SELECT source_key FROM lyrics_preparation_job WHERE source_key=?",source).isEmpty())return false;
            String request=UUID.randomUUID().toString();
            if(asset!=null) {
                if(!visible(work,asset))return false;
                var existing=timelines.find(asset);
                if(existing.isPresent()&&!existing.get().status().equals("NOT_REQUESTED"))return false;
                stageTimeline(asset,work,request,existing.isPresent());
            }
            insertJob(source,asset,work,sample,audio,task,request);
            return true;
        }));
    }
    private void stageTimeline(String asset,String work,String request,boolean exists) {
        if(!exists)db.update("INSERT INTO media_lyrics_timeline(asset_id,work_id,state,request_key,updated_at) VALUES(?,?,'PREPARING',?,CURRENT_TIMESTAMP)",asset,work,request);
        else db.update("UPDATE media_lyrics_timeline SET state='PREPARING',request_key=?,updated_at=CURRENT_TIMESTAMP WHERE asset_id=?",request,asset);
    }
    private void insertJob(String source,String asset,String work,String sample,String audio,String task,String request) {
        db.update("""
                INSERT INTO lyrics_preparation_job(source_key,asset_id,work_id,sample_id,audio_url,original_task_id,mode,state,request_key,next_attempt_at)
                VALUES(?,?,?,?,?,?,?,'PENDING',?,CURRENT_TIMESTAMP)
                """,source,asset,work,sample,audio,task,task==null?"TRANSCRIBE":"ORIGINAL",request);
    }
    private void lock(){db.queryForObject("SELECT kind FROM model_usage_lock WHERE kind='TIMING' FOR UPDATE",String.class);}
    private boolean visible(String work,String asset){return !db.queryForList("""
            SELECT m.id FROM media_asset m JOIN music_work w ON w.id=m.work_id
            LEFT JOIN media_asset_annotation a ON a.asset_id=m.id
            WHERE m.id=? AND m.work_id=? AND w.deleted_at IS NULL AND a.deleted_at IS NULL
            """,asset,work).isEmpty();}
    record Job(String source,String asset,String work,String sample,String audio,String task,String mode,String state,
               String request,String external,int attempts,String token) {}
    private Optional<Job> claim() {
        return tx.execute(status->{
            lock();
            // A lost submission cannot be retried. Known external tasks can safely resume polling.
            db.update("UPDATE lyrics_preparation_job SET state='UNKNOWN',error_code='SUBMISSION_UNKNOWN',lease_token=NULL,lease_until=NULL WHERE state='SUBMITTING' AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)");
            db.update("""
                    UPDATE lyrics_preparation_job SET state=CASE WHEN external_task_id IS NULL THEN 'UNKNOWN' ELSE 'POLLING' END,
                        error_code=CASE WHEN external_task_id IS NULL THEN 'MISSING_EXTERNAL_TASK' ELSE error_code END,
                        lease_token=NULL,lease_until=NULL WHERE state='READING' AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)
                    """);
            db.update("UPDATE lyrics_preparation_job SET state='UNKNOWN',error_code='PROVIDER_WAIT_EXPIRED' WHERE state='POLLING' AND last_submit_at<?",Timestamp.from(Instant.now().minusSeconds(1800)));
            db.update("""
                    UPDATE media_lyrics_timeline SET state='UNKNOWN',updated_at=CURRENT_TIMESTAMP
                    WHERE state IN ('PREPARING','FETCHING') AND asset_id IN
                    (SELECT j.asset_id FROM lyrics_preparation_job j WHERE j.state='UNKNOWN' AND j.request_key=media_lyrics_timeline.request_key)
                    """);
            // Waiting for a known result does not hold a worker slot between polls.
            if(db.queryForObject("SELECT COUNT(*) FROM lyrics_preparation_job WHERE state IN ('SUBMITTING','READING')",Integer.class)>0)return Optional.empty();
            boolean canTranscribe=transcription.isConfigured();
            var rows=db.query("""
                    SELECT source_key,asset_id,work_id,sample_id,audio_url,original_task_id,mode,state,request_key,external_task_id,attempts
                    FROM lyrics_preparation_job WHERE next_attempt_at<=CURRENT_TIMESTAMP AND
                        ((state='POLLING' AND ?=TRUE)
                         OR (state='PENDING' AND ((mode='ORIGINAL' AND ?=TRUE) OR (mode='TRANSCRIBE' AND ?=TRUE))))
                    ORDER BY CASE WHEN state='POLLING' THEN 0 ELSE 1 END,created_at,source_key LIMIT 1 FOR UPDATE
                    """,(r,n)->new Job(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getInt(11),UUID.randomUUID().toString()),
                    canTranscribe,original.isConfigured(),canTranscribe);
            if(rows.isEmpty())return Optional.empty();var job=rows.get(0);
            if(job.asset()!=null&&(!visible(job.work(),job.asset())||!timelines.find(job.asset()).map(s->job.request().equals(s.requestKey())).orElse(false))) {
                db.update("UPDATE lyrics_preparation_job SET state='CANCELLED',error_code='AUDIO_UNAVAILABLE' WHERE source_key=?",job.source());return Optional.empty();
            }
            boolean poll=job.state().equals("POLLING");
            if(!poll) {
                int used=db.queryForObject("SELECT COALESCE(SUM(attempts),0) FROM lyrics_preparation_job WHERE last_submit_at>=?",Integer.class,Timestamp.from(LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()));
                if(used>=dailyLimit)return Optional.empty();
            }
            db.update("""
                    UPDATE lyrics_preparation_job SET state=?,lease_token=?,lease_until=?,updated_at=CURRENT_TIMESTAMP,
                      attempts=attempts+?,last_submit_at=CASE WHEN ?=1 THEN CURRENT_TIMESTAMP ELSE last_submit_at END WHERE source_key=?
                    """,poll?"READING":"SUBMITTING",job.token(),Timestamp.from(Instant.now().plusSeconds(CLAIM_SECONDS)),poll?0:1,poll?0:1,job.source());
            return Optional.of(job);
        });
    }
    public void processOnce() {
        var next=claim();if(next.isEmpty())return;var job=next.get();
        try {
            if(job.state().equals("POLLING")) {
                var result=transcription.poll(job.external());
                db.update("UPDATE lyrics_preparation_job SET poll_failures=0 WHERE source_key=? AND lease_token=?",job.source(),job.token());
                if(result.state().equals("PENDING"))reschedule(job,"POLLING",null,10);
                else if(result.state().equals("FAILED"))rejected(job,"TRANSCRIPTION_FAILED");
                else if(result.state().equals("REVIEW"))finish(job,"REVIEW",List.of());
                else finish(job,result.state(),result.words());
            } else if(job.mode().equals("ORIGINAL")) {
                var words=original.fetch(job.task(),job.audio());finish(job,PlaybackLyrics.lines(words).isEmpty()?"EMPTY":"READY",words);
            } else {
                String external=transcription.submit(job.audio());
                db.update("UPDATE lyrics_preparation_job SET external_task_id=?,state='POLLING',lease_token=NULL,lease_until=NULL,next_attempt_at=?,updated_at=CURRENT_TIMESTAMP WHERE source_key=? AND lease_token=?",external,Timestamp.from(Instant.now().plusSeconds(10)),job.source(),job.token());
            }
        }catch(AudioTranscriptionProvider.Rejected error){rejected(job,error.code);}
        catch(LyricsTimingProvider.Rejected error){rejected(job,"ORIGINAL_UNAVAILABLE");}
        catch(RuntimeException error){
            if(job.state().equals("POLLING")) {
                int failures=db.queryForObject("SELECT poll_failures FROM lyrics_preparation_job WHERE source_key=?",Integer.class,job.source());
                db.update("UPDATE lyrics_preparation_job SET poll_failures=poll_failures+1 WHERE source_key=? AND lease_token=?",job.source(),job.token());
                reschedule(job,failures<4?"POLLING":"UNKNOWN","RESULT_READ_FAILED",30);
            } else reschedule(job,"UNKNOWN","SUBMISSION_UNKNOWN",0);
        }
    }
    private void rejected(Job job,String code) {
        // Only a definite rejection can lead to a new bounded attempt. Model authorization is terminal.
        if(job.mode().equals("ORIGINAL")&&job.attempts()<1&&transcriptionFallback&&transcription.isConfigured()) {
            db.update("UPDATE lyrics_preparation_job SET mode='TRANSCRIBE',state='PENDING',lease_token=NULL,lease_until=NULL,next_attempt_at=?,error_code=?,updated_at=CURRENT_TIMESTAMP WHERE source_key=? AND lease_token=?",Timestamp.from(Instant.now().plusSeconds(30)),code,job.source(),job.token());
        }else if(!job.mode().equals("ORIGINAL")&&job.attempts()<1&&!code.equals("PROVIDER_401")&&!code.equals("PROVIDER_402"))
            reschedule(job,"PENDING",code,60);
        else finish(job,"FAILED",List.of(),code);
    }
    private void reschedule(Job job,String state,String error,int seconds) {
        db.update("UPDATE lyrics_preparation_job SET state=?,error_code=?,lease_token=NULL,lease_until=NULL,next_attempt_at=?,updated_at=CURRENT_TIMESTAMP WHERE source_key=? AND lease_token=?",state,error,Timestamp.from(Instant.now().plusSeconds(seconds)),job.source(),job.token());
    }
    private void finish(Job job,String state,List<PlaybackLyrics.Word> words) {
        finish(job,state,words,null);
    }
    private void finish(Job job,String state,List<PlaybackLyrics.Word> words,String error) {
        tx.executeWithoutResult(status->{
            lock();
            if(job.asset()!=null&&!visible(job.work(),job.asset()))stateCancelled(job);
            else if(db.update("UPDATE lyrics_preparation_job SET state=?,result_json=?,error_code=?,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE source_key=? AND lease_token=?",state,json.writeValueAsString(words),error,job.source(),job.token())==1&&job.asset()!=null) {
                db.update("UPDATE media_lyrics_timeline SET state=?,words_json=?,updated_at=CURRENT_TIMESTAMP WHERE asset_id=? AND request_key=? AND state IN ('PREPARING','FETCHING','UNKNOWN')",state,json.writeValueAsString(words),job.asset(),job.request());
            }
        });
    }
    private void stateCancelled(Job job){db.update("UPDATE lyrics_preparation_job SET state='CANCELLED',lease_token=NULL,lease_until=NULL WHERE source_key=? AND lease_token=?",job.source(),job.token());}
    private Optional<PlaybackLyrics.Snapshot> packagedSample(String sample) {
        sampleKey(sample);
        // Curated, version-bound backfills are packaged with the release; playback stays read-only.
        var resource=new org.springframework.core.io.ClassPathResource("lyrics/samples/"+sample+".json");
        if(resource.exists())try(var stream=resource.getInputStream()) {
            var saved=json.readTree(stream.readAllBytes());
            if(sampleKey(sample).equals(saved.path("sourceKey").asText())) {
                if(saved.path("instrumental").asBoolean(false))
                    return Optional.of(new PlaybackLyrics.Snapshot("INSTRUMENTAL","",List.of(),false));
                var words=List.of(json.treeToValue(saved.path("words"),PlaybackLyrics.Word[].class));
                var lines=PlaybackLyrics.lines(words);
                if(!lines.isEmpty())return Optional.of(new PlaybackLyrics.Snapshot("READY",lines.stream().map(PlaybackLyrics.Line::text).collect(java.util.stream.Collectors.joining("\n")),lines,false));
            }
        }catch(java.io.IOException error){throw new IllegalStateException("热歌歌词时间轴读取失败",error);}

        return Optional.empty();
    }
    public PlaybackLyrics.Snapshot sample(String sample) {
        var packaged=packagedSample(sample);if(packaged.isPresent())return packaged.get();
        var rows=db.queryForList("SELECT state,result_json FROM lyrics_preparation_job WHERE source_key=?",sampleKey(sample));
        if(rows.isEmpty())return new PlaybackLyrics.Snapshot("EMPTY","",List.of(),false);
        var row=rows.get(0);String state=(String)row.get("state");
        List<PlaybackLyrics.Word> words=row.get("result_json")==null?List.of():List.of(json.readValue((String)row.get("result_json"),PlaybackLyrics.Word[].class));
        var lines=state.equals("READY")?PlaybackLyrics.lines(words):List.<PlaybackLyrics.Line>of();
        return new PlaybackLyrics.Snapshot(Set.of("PENDING","SUBMITTING","READING","POLLING").contains(state)?"PREPARING":state,
                lines.stream().map(PlaybackLyrics.Line::text).collect(java.util.stream.Collectors.joining("\n")),lines,false);
    }
}
