package com.qingfeng.music.server.web;
import com.qingfeng.music.media.api.MediaApi.Content;
import org.springframework.core.io.*;
import org.springframework.http.*;
import java.io.*;
import java.net.URI;

public final class AudioResponse {
 private AudioResponse(){}
 public static ResponseEntity<Resource> from(Content content,boolean download){
  Resource resource=new AbstractResource(){
   public String getDescription(){return "authorized-audio";}
   public InputStream getInputStream()throws IOException{return content.source().open();}
   public long contentLength(){return content.size();}
   public String getFilename(){return content.filename();}
  };
  var disposition=(download?ContentDisposition.attachment():ContentDisposition.inline()).filename(content.filename()).build();
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString()).header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(content.contentType())).body(resource);
 }
 /** 302 仅用于 oss: 且非下载的场景；no-store 避免浏览器复用过期签名，no-referrer 避免同源地址外泄。 */
 public static ResponseEntity<Resource> redirect(URI location){
  return ResponseEntity.status(HttpStatus.FOUND).cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").location(location).build();
 }
}
