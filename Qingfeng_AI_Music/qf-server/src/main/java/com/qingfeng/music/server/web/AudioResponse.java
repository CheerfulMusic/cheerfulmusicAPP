package com.qingfeng.music.server.web;
import com.qingfeng.music.media.api.MediaApi.Content;
import org.springframework.core.io.*;
import org.springframework.http.*;
import java.io.*;

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
}
