package com.qingfeng.music.cover.api;
import com.qingfeng.music.media.api.MediaApi.Content;
public interface SharedCoverAccess {Content content(String work,String cover);String current(String work);}
