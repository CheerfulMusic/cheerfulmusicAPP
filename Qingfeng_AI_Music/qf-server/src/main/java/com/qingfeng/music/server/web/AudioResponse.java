package com.qingfeng.music.server.web;
import com.qingfeng.music.media.api.MediaApi.Content;
import org.springframework.core.io.*;
import org.springframework.http.*;
import java.io.*;
import java.net.URI;

public final class AudioResponse {
 private AudioResponse(){}
 public static ResponseEntity<Resource> from(Content content,boolean download){return from(content,download,null);}
 public static ResponseEntity<Resource> from(Content content,boolean download,String range){
  long size=content.size(),start=0,end=size-1;boolean partial=false;
  if(range!=null&&range.startsWith("bytes=")&&size>0){
   try{
    String spec=range.substring(6);
    if(spec.contains(","))throw new IllegalArgumentException();
    String[] parts=spec.split("-",-1);
    if(parts.length!=2||parts[0].isEmpty()&&parts[1].isEmpty())throw new IllegalArgumentException();
    if(parts[0].isEmpty()){long suffix=Long.parseLong(parts[1]);if(suffix<=0)throw new IllegalArgumentException();start=Math.max(0,size-suffix);}
    else{start=Long.parseLong(parts[0]);if(!parts[1].isEmpty())end=Math.min(end,Long.parseLong(parts[1]));}
    if(start<0||start>=size||end<start)throw new IllegalArgumentException();
    partial=true;
   }catch(IllegalArgumentException e){return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).header(HttpHeaders.CONTENT_RANGE,"bytes */"+size).header(HttpHeaders.ACCEPT_RANGES,"bytes").build();}
  }
  final long offset=start,length=end-start+1;
  Resource resource=new AbstractResource(){
   public String getDescription(){return "authorized-audio";}
   public InputStream getInputStream()throws IOException{return new BoundedInputStream(content.source().open(offset),length);}
   public long contentLength(){return length;}
   public String getFilename(){return content.filename();}
  };
  var disposition=(download?ContentDisposition.attachment():ContentDisposition.inline()).filename(content.filename()).build();
  var response=ResponseEntity.status(partial?HttpStatus.PARTIAL_CONTENT:HttpStatus.OK).cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString()).header(HttpHeaders.ACCEPT_RANGES,"bytes").header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(content.contentType())).contentLength(length);
  if(partial)response.header(HttpHeaders.CONTENT_RANGE,"bytes "+start+"-"+end+"/"+size);
  return response.body(resource);
 }
 private static final class BoundedInputStream extends FilterInputStream{
  private long remaining;
  BoundedInputStream(InputStream source,long length){super(source);remaining=length;}
  public int read()throws IOException{if(remaining==0)return -1;int value=super.read();if(value!=-1)remaining--;return value;}
  public int read(byte[] bytes,int offset,int length)throws IOException{if(remaining==0)return -1;int count=super.read(bytes,offset,(int)Math.min(length,remaining));if(count>0)remaining-=count;return count;}
 }
 /** 302 仅用于 oss: 且非下载的场景；no-store 避免浏览器复用过期签名，no-referrer 避免同源地址外泄。 */
 public static ResponseEntity<Resource> redirect(URI location){
  return ResponseEntity.status(HttpStatus.FOUND).cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").location(location).build();
 }
}
