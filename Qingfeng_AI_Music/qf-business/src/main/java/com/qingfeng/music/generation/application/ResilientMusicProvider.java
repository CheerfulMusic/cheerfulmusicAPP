package com.qingfeng.music.generation.application;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.settings.MusicPromptLimits;
import java.time.*;
import java.util.logging.Logger;

/** 只熔断新提交，查询和归档不受影响。进程内冷却不是供应商幂等或自动重发机制。 */
public final class ResilientMusicProvider implements MusicProvider {
    private static final Logger LOG=Logger.getLogger(ResilientMusicProvider.class.getName());
    private final MusicProvider delegate;
    private final Clock clock;
    private Instant blockedUntil=Instant.EPOCH,lastFailure=Instant.EPOCH;
    private int failures;
    public ResilientMusicProvider(MusicProvider delegate,Clock clock){this.delegate=delegate;this.clock=clock;}
    public String name(){return delegate.name();}
    public boolean isConfigured(){return delegate.isConfigured();}
    public MusicPromptLimits limits(){return delegate.limits();}
    public void validate(Request request){delegate.validate(request);}
    public Request prepare(Request request){return delegate.prepare(request);}
    public synchronized void checkAdmission(){
        if(clock.instant().isBefore(blockedUntil))throw new DomainException("MUSIC_PROVIDER_COOLDOWN_UNAVAILABLE","音乐生成服务正在恢复，你的歌词和设置已保留，请稍后再试。尚未提交新的生成。");
        delegate.checkAdmission();
    }
    public Submission submit(Request request,String key){return send(()->delegate.submit(request,key));}
    public Submission replace(Request request,Replacement edit,String key){return send(()->delegate.replace(request,edit,key));}
    private Submission send(java.util.function.Supplier<Submission> call){
        try{checkAdmission();}catch(DomainException e){throw new NotSubmitted("PROVIDER_COOLDOWN");}
        try{var accepted=call.get();succeeded();return accepted;}
        catch(Rejected e){failed(e.code());throw e;}
        catch(NotSubmitted e){failed(e.code());throw e;}
        catch(RuntimeException e){failed("SUBMISSION_UNKNOWN");throw e;}
    }
    private synchronized void succeeded(){failures=0;blockedUntil=Instant.EPOCH;}
    private synchronized void failed(String code){
        Instant now=clock.instant();if(lastFailure.plusSeconds(300).isBefore(now))failures=0;lastFailure=now;
        int wait=switch(code){case "PROVIDER_AUTH_FAILED","PROVIDER_BALANCE_LOW","PROVIDER_ENDPOINT_UNAVAILABLE"->600;case "PROVIDER_RATE_LIMITED"->60;case "PROVIDER_INPUT_REJECTED"->0;default->++failures>=3?60:0;};
        if(wait>0){blockedUntil=now.plusSeconds(wait);LOG.warning("music_provider_cooldown provider="+name()+" code="+code+" seconds="+wait);}
    }
    public Result query(String id){return delegate.query(id);}
    public boolean supportsSectionEditing(){return delegate.supportsSectionEditing();}
    public AudioInfo sourceInfo(String id,String url){return delegate.sourceInfo(id,url);}
}
