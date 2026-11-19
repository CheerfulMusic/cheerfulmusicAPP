package com.qingfeng.music.infrastructure.storage;

import com.qingfeng.music.media.application.MediaFileStore;
import com.qingfeng.music.common.exception.DomainException;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 文件名由服务端生成；临时文件、大小上限、容器头检查和路径约束均在此执行。 */
public final class LocalMediaFileStore implements MediaFileStore {
    private static final long LIMIT=20L*1024*1024;
    private static final long WAV_LIMIT = 100L * 1024 * 1024;
    private final Path root;
    private final String wavEncoder;

    public LocalMediaFileStore(Path root) throws IOException { this(root, null); }

    public LocalMediaFileStore(Path root, String wavEncoder) throws IOException {
        Files.createDirectories(root);
        this.root = root.toRealPath();
        this.wavEncoder = wavEncoder;
    }
    public Saved save(InputStream content)throws IOException{
        Path temporary=Files.createTempFile(root,"upload-",".part");
        Path converted = null;
        try(var input=new BufferedInputStream(content)){
            byte[] header=input.readNBytes(12);String type;
            if(header.length==12&&header[0]=='R'&&header[1]=='I'&&header[2]=='F'&&header[3]=='F'&&header[8]=='W'&&header[9]=='A'&&header[10]=='V'&&header[11]=='E')type="audio/wav";
            else if(header.length>=3&&((header[0]=='I'&&header[1]=='D'&&header[2]=='3')||((header[0]&255)==255&&(header[1]&224)==224)))type="audio/mpeg";
            else throw new DomainException("INVALID_AUDIO","仅支持具有有效容器头的WAV或MP3音频");
            MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            long size=header.length;
            long limit = wavEncoder != null && type.equals("audio/wav") ? WAV_LIMIT : LIMIT;
            try(var output=Files.newOutputStream(temporary)){output.write(header);digest.update(header);byte[] buffer=new byte[8192];int n;
                while((n=input.read(buffer))!=-1){size+=n;if(size>limit)throw new DomainException("INVALID_AUDIO","音频文件超过归档大小限制");output.write(buffer,0,n);digest.update(buffer,0,n);}
            }
            if(type.equals("audio/wav")&&size<44)throw new DomainException("INVALID_AUDIO","WAV文件不完整");
            Path archived = temporary;
            if (wavEncoder != null && type.equals("audio/mpeg")) {
                converted = Files.createTempFile(root, "wav-", ".part");
                encodeWav(temporary, converted);
                archived = converted;
                size = Files.size(archived);
                type = "audio/wav";
                digest.reset();
                try (var wav = new DigestInputStream(Files.newInputStream(archived), digest)) {
                    wav.transferTo(OutputStream.nullOutputStream());
                }
            }
            String key=UUID.randomUUID()+(type.equals("audio/wav")?".wav":".mp3");Path destination=resolve(key);
            try{Files.move(archived,destination,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(archived,destination);}
            return new Saved(key,size,type,HexFormat.of().formatHex(digest.digest()));
        } finally {
            try { Files.deleteIfExists(temporary); }
            finally { if (converted != null) Files.deleteIfExists(converted); }
        }
    }

    private void encodeWav(Path source, Path target) throws IOException {
        long stopAt = WAV_LIMIT + 1024 * 1024;
        var process = new ProcessBuilder(wavEncoder, "-nostdin", "-v", "error", "-xerror", "-y", "-f", "mp3",
                "-i", source.toString(), "-map", "0:a:0", "-c:a", "pcm_s16le", "-fs",
                Long.toString(stopAt), "-f", "wav", target.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(90, TimeUnit.SECONDS)) throw new IOException("WAV conversion timed out");
            var size = Files.size(target);
            if (process.exitValue() != 0 || size <= 44) throw new DomainException("INVALID_AUDIO", "MP3 无法转换为 WAV");
            if (size > WAV_LIMIT) throw new DomainException("INVALID_AUDIO", "转换后的 WAV 超过 100 MB");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("WAV conversion interrupted", e);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
    public InputStream open(String key)throws IOException{
        Path file=resolve(key);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().startsWith(root))throw new FileNotFoundException("音频资源不存在");
        return Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS);
    }
    public void discard(String key)throws IOException{Files.deleteIfExists(resolve(key));}
    public boolean exists(String key){return Files.isRegularFile(resolve(key),LinkOption.NOFOLLOW_LINKS);}
    private Path resolve(String key){if(key==null||!key.matches("[0-9a-f-]{36}\\.(wav|mp3)"))throw new DomainException("INVALID_MEDIA_KEY","非法资源标识");Path file=root.resolve(key).normalize();if(!file.getParent().equals(root))throw new DomainException("INVALID_MEDIA_KEY","非法资源路径");return file;}
}
