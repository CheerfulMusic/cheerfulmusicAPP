package com.qingfeng.music.infrastructure.storage;
import com.qingfeng.music.cover.application.CoverFileStore;
import com.qingfeng.music.common.exception.DomainException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import java.io.*;
import java.nio.file.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.UUID;

/** 图片先解码、限制像素，再重编码为方形JPEG；不保留元数据或用户文件名。 */
public final class CoverImageStore implements CoverFileStore {
 private final Path root;private final OSS oss;private final String bucket,prefix;private final boolean cloud;
 public CoverImageStore(Path root,OSS oss,String bucket,String prefix,boolean cloud)throws IOException{Files.createDirectories(root);this.root=root.toRealPath();this.oss=oss;this.bucket=bucket;this.prefix=prefix.endsWith("/")?prefix:prefix+"/";this.cloud=cloud;if(!this.prefix.matches("[a-zA-Z0-9/_-]+/"))throw new IllegalArgumentException("封面存储前缀无效");}
 public Stored save(InputStream source)throws IOException{
  byte[] data=source.readNBytes(5*1024*1024+1);if(data.length>5*1024*1024)throw invalid();
  BufferedImage image;
  try(var in=ImageIO.createImageInputStream(new ByteArrayInputStream(data))){var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw invalid();var reader=readers.next();try{reader.setInput(in);int w=reader.getWidth(0),h=reader.getHeight(0);String type=reader.getFormatName();if(w<16||h<16||w>8000||h>8000||(long)w*h>16000000||!java.util.Set.of("JPEG","JPG","PNG").contains(type.toUpperCase(java.util.Locale.ROOT)))throw invalid();image=reader.read(0);}finally{reader.dispose();}}
  if(image==null)throw invalid();int side=Math.min(image.getWidth(),image.getHeight());var output=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_RGB);var g=output.createGraphics();try{g.setColor(Color.WHITE);g.fillRect(0,0,1024,1024);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);int x=(image.getWidth()-side)/2,y=(image.getHeight()-side)/2;g.drawImage(image,0,0,1024,1024,x,y,x+side,y+side,null);}finally{g.dispose();image.flush();}
  var encoded=new ByteArrayOutputStream();ImageIO.write(output,"jpeg",encoded);output.flush();byte[] jpeg=encoded.toByteArray();String name=UUID.randomUUID()+".jpg";
  if(cloud){var metadata=new ObjectMetadata();metadata.setContentType("image/jpeg");metadata.setContentLength(jpeg.length);oss.putObject(bucket,prefix+name,new ByteArrayInputStream(jpeg),metadata);return new Stored("oss:"+prefix+name,jpeg.length);}
  Path temp=Files.createTempFile(root,"cover-",".part");try{Files.write(temp,jpeg);try{Files.move(temp,root.resolve(name),StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,root.resolve(name));}}finally{Files.deleteIfExists(temp);}return new Stored("local:"+name,jpeg.length);
 }
 public InputStream open(String key)throws IOException{if(key.startsWith("oss:")){cloudKey(key);return oss.getObject(bucket,key.substring(4)).getObjectContent();}Path file=local(key);if(!file.toRealPath().startsWith(root)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new FileNotFoundException("封面不存在");return Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS);}
 public void discard(String key)throws IOException{if(key.startsWith("oss:")){cloudKey(key);oss.deleteObject(bucket,key.substring(4));}else Files.deleteIfExists(local(key));}
 private Path local(String key){if(key==null||!key.matches("local:[0-9a-f-]{36}\\.jpg"))throw invalid();return root.resolve(key.substring(6));}
 private void cloudKey(String key){if(oss==null||!key.matches("oss:"+java.util.regex.Pattern.quote(prefix)+"[0-9a-f-]{36}\\.jpg"))throw invalid();}
 private DomainException invalid(){return new DomainException("INVALID_COVER_IMAGE","请上传有效的JPG或PNG图片，最大5MB、1600万像素");}
}
