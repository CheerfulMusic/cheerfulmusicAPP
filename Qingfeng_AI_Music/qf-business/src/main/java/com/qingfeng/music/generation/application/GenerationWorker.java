package com.qingfeng.music.generation.application;

import com.qingfeng.music.generation.application.port.*;
import com.qingfeng.music.generation.domain.GenerationStatus;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;

/** 持久化每一步后再调用外部服务。中断的提交转待核对，只有查询和归档可自动重试。 */
public final class GenerationWorker {
    private final ExecutionRepository repository;
    private final MusicProvider provider;
    private final AudioDownloader downloader;
    private final GeneratedAudioArchive archive;
    private final Clock clock;
    private final Duration lease;
    private final int maxArchiveAttempts;
    private SectionEditRepository edits;
    private AudioCandidates candidates;
    public GenerationWorker withCandidates(AudioCandidates value){candidates=value;return this;}
    private com.qingfeng.music.settings.MusicSettings settings;
    public GenerationWorker withSettings(com.qingfeng.music.settings.MusicSettings value){settings=value;return this;}
    public GenerationWorker withSectionEdits(SectionEditRepository edits){this.edits=edits;return this;}

    public GenerationWorker(ExecutionRepository repository, MusicProvider provider,
                            AudioDownloader downloader, GeneratedAudioArchive archive,
                            Clock clock, Duration lease, int maxArchiveAttempts) {
        this.repository=repository; this.provider=provider; this.downloader=downloader;
        this.archive=archive; this.clock=clock; this.lease=lease; this.maxArchiveAttempts=maxArchiveAttempts;
    }

    private Instant now() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    public boolean runOnce() {
        if (!provider.isConfigured()) return false;
        String token=UUID.randomUUID().toString(); Instant now=now();
        if (!repository.lockProvider(provider.name(),token,now,now.plus(lease))) return false;
        String taskId=null;ScheduledExecutorService heartbeat=null;
        try {
            var found=repository.claim(provider.name(),token,now,now.plus(lease));
            if (found.isEmpty()) return false;
            var execution=found.get(); taskId=execution.task().id();
            String claimedTask=taskId;
            heartbeat=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"generation-lease-heartbeat");thread.setDaemon(true);return thread;});
            heartbeat.scheduleAtFixedRate(()->{try{repository.renew(provider.name(),claimedTask,token,now(),now().plus(lease));}catch(RuntimeException ignored){/* 不延长失败租约，后续更新仍受令牌条件保护。 */}},20,20,TimeUnit.SECONDS);
            switch(execution.task().status()) {
                case QUEUED -> submit(execution);
                case SUBMITTING -> repository.unknown(taskId,token,"WORKER_INTERRUPTED",now());
                case RUNNING, SUBMISSION_UNKNOWN -> query(execution);
                case SAVING -> save(execution);
                default -> { }
            }
            return true;
        } finally {
            if(heartbeat!=null)heartbeat.shutdownNow();
            if (taskId!=null) repository.release(taskId,token);
            repository.unlockProvider(provider.name(),token);
        }
    }

    private void submit(ExecutionRepository.Execution execution) {
        var task=execution.task(); String token=execution.leaseToken();
        if (!repository.startSubmission(task.id(),token,now())) return;
        String attempt=repository.attempt(task.id(),"SUBMIT",now());
        try {
            var direction=settings==null?null:settings.frozen(task.workId(),task.id()).orElse(null);
            var request=direction==null?new MusicProvider.Request(task.title(),task.lyrics(),task.style(),task.vocalGender()):new MusicProvider.Request(task.title(),task.lyrics(),direction.style(),task.vocalGender(),direction.negativeTags(),true);
            var edit=edits==null?java.util.Optional.<SectionEditRepository.Edit>empty():edits.edit(task.id());
            var accepted=edit.isPresent()?provider.replace(request,edit.get().replacement(),task.id()):provider.submit(request,task.id());
            if (accepted==null || accepted.providerTaskId()==null || accepted.providerTaskId().isBlank()
                || accepted.providerTaskId().length()>255) throw new IllegalStateException("缺少供应商任务标识");
            // 已知成功的迟到响应也可恢复待核对任务；不能因此再次提交。
            repository.accepted(task.id(),accepted.providerTaskId(),now(),now().plusSeconds(10));
            repository.finishAttempt(attempt,"ACCEPTED",null,now());
        } catch (MusicProvider.Rejected rejected) {
            repository.failed(task.id(),token,"PROVIDER_REJECTED",now());
            repository.finishAttempt(attempt,"REJECTED","PROVIDER_REJECTED",now());
        } catch (RuntimeException unknown) {
            repository.unknown(task.id(),token,"SUBMISSION_UNKNOWN",now());
            repository.finishAttempt(attempt,"UNKNOWN","SUBMISSION_UNKNOWN",now());
        }
    }

    private void query(ExecutionRepository.Execution execution) {
        var task=execution.task(); String token=execution.leaseToken();
        if (execution.providerTaskId()==null) { repository.unknown(task.id(),token,"MISSING_PROVIDER_TASK",now()); return; }
        String attempt=repository.attempt(task.id(),"QUERY",now());
        try {
            var result=provider.query(execution.providerTaskId());
            if (result==null || !execution.providerTaskId().equals(result.providerTaskId())) throw new IllegalStateException("供应商查询响应不匹配");
            if (result.status()==GenerationStatus.FAILED) repository.failed(task.id(),token,"PROVIDER_FAILED",now());
            else if (result.status()==GenerationStatus.SAVING || result.status()==GenerationStatus.SUCCEEDED) {
                if (result.temporaryAudioUrl()==null || result.temporaryAudioUrl().length()>4096) throw new IllegalStateException("缺少音频地址");
                if(edits!=null&&result.audioInfo()!=null)edits.recordAudio(task.id(),result.audioInfo());
                if(candidates!=null&&(edits==null||edits.edit(task.id()).isEmpty()))candidates.remember(task.id(),result.candidates());
                repository.saving(task.id(),token,result.temporaryAudioUrl(),now());
            } else repository.running(task.id(),token,0,null,now(),now().plusSeconds(10));
            repository.finishAttempt(attempt,"COMPLETED",null,now());
        } catch (RuntimeException error) {
            int failures=execution.queryFailures()+1;
            repository.running(task.id(),token,failures,"QUERY_RETRY",now(),now().plusSeconds(Math.min(300,10L<<Math.min(5,failures))));
            repository.finishAttempt(attempt,"RETRY","QUERY_RETRY",now());
        }
    }

    private void save(ExecutionRepository.Execution execution) {
        var task=execution.task(); String token=execution.leaseToken();
        String attempt=repository.attempt(task.id(),"ARCHIVE",now());
        try {
            String asset=null;
            var tracks=candidates==null?java.util.List.<AudioCandidates.Candidate>of():candidates.get(task.id());
            if(tracks.isEmpty()){
                try(var input=downloader.open(execution.audioUrl())){asset=archive.store(task.id(),task.workId(),input);}
            }else for(var track:tracks){
                String saved=archive.existingCandidate(task.id(),track.key()).orElse(null);
                if(saved==null)try(var input=downloader.open(track.audio().audioUrl())){saved=archive.storeCandidate(task.id(),task.workId(),track.key(),track.audio(),input);}
                if(track.key().equals("primary"))asset=saved;
            }
            if(asset==null)throw new IllegalStateException("主候选音频未保存");
            repository.succeeded(task.id(),token,asset,now());
            repository.finishAttempt(attempt,"COMPLETED",null,now());
        } catch (Exception error) {
            // 临时下载地址可能已过期。只读查询可刷新地址，绝不重新提交生成。
            if(error instanceof java.io.IOException && execution.providerTaskId()!=null){
                try{var latest=provider.query(execution.providerTaskId());if(latest!=null&&execution.providerTaskId().equals(latest.providerTaskId())&&latest.temporaryAudioUrl()!=null&&latest.temporaryAudioUrl().length()<=4096&&(latest.status()==GenerationStatus.SAVING||latest.status()==GenerationStatus.SUCCEEDED)){if(edits!=null&&latest.audioInfo()!=null)edits.recordAudio(task.id(),latest.audioInfo());if(candidates!=null&&!candidates.get(task.id()).isEmpty())candidates.remember(task.id(),latest.candidates());repository.refreshAudioUrl(task.id(),token,latest.temporaryAudioUrl());}}
                catch(RuntimeException ignored){/* 保留失败状态与已有外部任务标识，稍后只重试归档。 */}
            }
            int attempts=execution.archiveAttempts()+1;
            repository.archiveFailed(task.id(),token,attempts,attempts>=maxArchiveAttempts,now(),now().plusSeconds(Math.min(300,15L<<Math.min(4,attempts))));
            repository.finishAttempt(attempt,"RETRY","AUDIO_ARCHIVE_FAILED",now());
        }
    }
}
