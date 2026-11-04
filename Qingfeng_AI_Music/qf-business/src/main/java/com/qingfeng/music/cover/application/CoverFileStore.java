package com.qingfeng.music.cover.application;
import java.io.*;
public interface CoverFileStore {
 Stored save(InputStream input)throws IOException;
 InputStream open(String key)throws IOException;
 void discard(String key)throws IOException;
 record Stored(String key,long size){}
}
