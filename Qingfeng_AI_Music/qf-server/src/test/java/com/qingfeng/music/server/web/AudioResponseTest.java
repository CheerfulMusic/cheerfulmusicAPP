package com.qingfeng.music.server.web;

import com.qingfeng.music.media.api.MediaApi.Content;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class AudioResponseTest {
 private final Content content=new Content(10,"audio/mpeg","song.mp3",()->new ByteArrayInputStream("0123456789".getBytes()));

 @Test void servesRequestedBytes()throws Exception{
  var response=AudioResponse.from(content,false,"bytes=3-5");
  assertEquals(HttpStatus.PARTIAL_CONTENT,response.getStatusCode());
  assertEquals("bytes 3-5/10",response.getHeaders().getFirst("Content-Range"));
  assertEquals(3,response.getHeaders().getContentLength());
  try(var input=response.getBody().getInputStream()){assertEquals("345",new String(input.readAllBytes()));}
 }

 @Test void servesSuffixAndRejectsOutOfBounds()throws Exception{
  var suffix=AudioResponse.from(content,false,"bytes=-2");
  try(var input=suffix.getBody().getInputStream()){assertEquals("89",new String(input.readAllBytes()));}
  var invalid=AudioResponse.from(content,false,"bytes=10-");
  assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,invalid.getStatusCode());
  assertEquals("bytes */10",invalid.getHeaders().getFirst("Content-Range"));
 }
}
