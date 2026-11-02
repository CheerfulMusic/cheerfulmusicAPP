package com.qingfeng.music.infrastructure.storage;

import com.qingfeng.music.media.application.MediaFileStore;
import com.qingfeng.music.common.exception.DomainException;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 文件名由服务端生成；临时文件、大小上限、容器头检查和路径约束均在此执行。 */
public final class LocalMediaFileStore implements MediaFileStore {
    private static final long LIMIT=20L*1024*1024;
    private final Path root;
    public LocalMediaFileStore(Path root)throws IOException{Files.createDirectories(root);this.root=root.toRealPath();}
    public Saved save(InputStream content)throws IOException{
        Path temporary=Files.createTempFile(root,"upload-",".part");boolean moved=false;
        try(var input=new BufferedInputStream(content)){
            byte[] header=input.readNBytes(12);String type;
            if(header.length==12&&header[0]=='R'&&header[1]=='I'&&header[2]=='F'&&header[3]=='F'&&header[8]=='W'&&header[9]=='A'&&header[10]=='V'&&header[11]=='E')type="audio/wav";
            else if(header.length>=3&&((header[0]=='I'&&header[1]=='D'&&header[2]=='3')||((header[0]&255)==255&&(header[1]&224)==224)))type="audio/mpeg";
            else throw new DomainException("INVALID_AUDIO","仅支持具有有效容器头的WAV或MP3音频");
            MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            long size=header.length;
            try(var output=Files.newOutputStream(temporary)){output.write(header);digest.update(header);byte[] buffer=new byte[8192];int n;
                while((n=input.read(buffer))!=-1){size+=n;if(size>LIMIT)throw new DomainException("INVALID_AUDIO","音频不能超过20MB");output.write(buffer,0,n);digest.update(buffer,0,n);}
            }
            if(type.equals("audio/wav")&&size<44)throw new DomainException("INVALID_AUDIO","WAV文件不完整");
            String key=UUID.randomUUID()+(type.equals("audio/wav")?".wav":".mp3");Path destination=resolve(key);
            try{Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,destination);}
            moved=true;return new Saved(key,size,type,HexFormat.of().formatHex(digest.digest()));
        }finally{if(!moved)Files.deleteIfExists(temporary);}
    }
    public InputStream open(String key)throws IOException{
        Path file=resolve(key);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().startsWith(root))throw new FileNotFoundException("音频资源不存在");
        return Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS);
    }
    public void discard(String key)throws IOException{Files.deleteIfExists(resolve(key));}
    public boolean exists(String key){return Files.isRegularFile(resolve(key),LinkOption.NOFOLLOW_LINKS);}
    private Path resolve(String key){if(key==null||!key.matches("[0-9a-f-]{36}\\.(wav|mp3)"))throw new DomainException("INVALID_MEDIA_KEY","非法资源标识");Path file=root.resolve(key).normalize();if(!file.getParent().equals(root))throw new DomainException("INVALID_MEDIA_KEY","非法资源路径");return file;}
}
