package com.qingfeng.music.server.mv;

import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.common.exception.DomainException;
import java.nio.file.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

/** Pricing uses the owned audio file, never a duration supplied by the browser or model. */
public final class ProbedAudioDuration implements AudioDuration {
    private final MediaApi media; private final Path root; private final String ffprobe;
    public ProbedAudioDuration(MediaApi media, Path root, String ffprobe) { this.media=media; this.root=root; this.ffprobe=ffprobe; }
    public long millis(String owner, String work, String asset) {
        Path file=null, result=null;
        try {
            var audio=media.open(owner,work,asset);
            if(audio.size()>100L*1024*1024) throw new IllegalArgumentException();
            Files.createDirectories(root); file=Files.createTempFile(root,"duration-",".audio"); result=Files.createTempFile(root,"duration-",".txt");
            try(var input=audio.source().open();var output=Files.newOutputStream(file)) {
                byte[] bytes=new byte[65536];long total=0;
                for(int n;(n=input.read(bytes))!=-1;){total+=n;if(total>100L*1024*1024)throw new IllegalArgumentException();output.write(bytes,0,n);}
            }
            var process=new ProcessBuilder(ffprobe,"-v","error","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",file.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(result.toFile()).start();
            try { if(!process.waitFor(30,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalArgumentException(); }
            finally { if(process.isAlive())process.destroyForcibly(); }
            if(Files.size(result)>100)throw new IllegalArgumentException();
            long millis=new BigDecimal(Files.readString(result).trim()).multiply(BigDecimal.valueOf(1000)).setScale(0,RoundingMode.CEILING).longValueExact();
            if(millis<2000||millis>3_600_000)throw new DomainException("VIDEO_AUDIO_DURATION_UNSUPPORTED","MV 支持 2 秒至 60 分钟的完整歌曲。");
            return millis;
        } catch(DomainException e){throw e;}
        catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new DomainException("VIDEO_AUDIO_DURATION_UNAVAILABLE","无法读取歌曲时长，未生成或扣费，请稍后重试。");}
        finally { for(Path p:new Path[]{file,result})if(p!=null)try{Files.deleteIfExists(p);}catch(java.io.IOException ignored){} }
    }
}
