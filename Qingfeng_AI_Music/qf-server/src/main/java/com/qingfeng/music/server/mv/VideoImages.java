package com.qingfeng.music.server.mv;

import com.qingfeng.music.common.exception.DomainException;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/** Owned local stills; never sent to Wan or used as filesystem paths supplied by users. */
public final class VideoImages {
    private final Path root;
    public VideoImages(Path root){this.root=root;}
    private Path folder(String owner,String work){
        try{return root.resolve(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((owner+":"+work).getBytes(java.nio.charset.StandardCharsets.UTF_8))));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public Path resolve(String owner,String work,String id){
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw invalid();
        Path p=folder(owner,work).resolve(id+".jpg");if(!Files.isRegularFile(p)||Files.isSymbolicLink(p))throw invalid();return p;
    }
    public synchronized String save(String owner,String work,InputStream input){
        try{
            byte[] data=input.readNBytes(12*1024*1024+1);if(data.length>12*1024*1024)throw invalid();
            BufferedImage source;
            try(var imageInput=ImageIO.createImageInputStream(new ByteArrayInputStream(data))){
                var readers=ImageIO.getImageReaders(imageInput);if(!readers.hasNext())throw invalid();var reader=readers.next();
                try{reader.setInput(imageInput);String type=reader.getFormatName();if(!Set.of("JPEG","JPG","PNG").contains(type.toUpperCase(Locale.ROOT)))throw invalid();
                    int w=reader.getWidth(0),h=reader.getHeight(0);if(w<=0||h<=0||(long)w*h>24_000_000)throw invalid();source=reader.read(0);
                }finally{reader.dispose();}
            }
            int w=source.getWidth(),h=source.getHeight();double scale=Math.min(1,2400.0/Math.max(w,h));
            var image=new BufferedImage(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)),BufferedImage.TYPE_INT_RGB);
            var graphics=image.createGraphics();try{graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,image.getWidth(),image.getHeight());graphics.drawImage(source,0,0,image.getWidth(),image.getHeight(),null);}finally{graphics.dispose();}
            Path directory=folder(owner,work);Files.createDirectories(directory);
            try(var files=Files.list(directory)){if(files.limit(100).count()>=100)throw new DomainException("VIDEO_IMAGE_LIMIT","每首歌曲最多上传 100 张 MV 图片，可重复使用已有图片。");}
            String id=UUID.randomUUID().toString();Path target=directory.resolve(id+".jpg");
            try(var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){if(!ImageIO.write(image,"jpg",output))throw invalid();}
            return id;
        }catch(DomainException e){throw e;}catch(IOException e){throw invalid();}
    }
    private static DomainException invalid(){return new DomainException("VIDEO_IMAGE_INVALID","请使用本人为该歌曲上传的 JPG 或 PNG 图片，最大 12 MB、2400 万像素。");}
}
