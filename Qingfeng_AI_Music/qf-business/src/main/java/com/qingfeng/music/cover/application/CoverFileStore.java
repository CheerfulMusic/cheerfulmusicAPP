package com.qingfeng.music.cover.application;
import java.io.*;
public interface CoverFileStore {
 Stored save(InputStream input)throws IOException;
 default Stored saveTitled(InputStream input,String title)throws IOException{
  throw new IOException("Cover title rendering is unavailable");
 }
 InputStream open(String key)throws IOException;
 void discard(String key)throws IOException;
 record Stored(String key,long size){}
}
