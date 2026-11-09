package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.server.hotsong.HotSongService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** 首页热歌推荐的公开读取与播放计次；播放不需要登录，也不返回任何个人数据。 */
@RestController @Profile({"mysql","test"}) @RequestMapping("/api/public/hot-songs")
public class HotSongController {
    private final HotSongService songs;
    public HotSongController(HotSongService songs){this.songs=songs;}

    @GetMapping public Map<String,Object> list(){return Map.of("songs",songs.stats());}

    @PostMapping("/{trackId}/play") public HotSongService.Stat play(@PathVariable String trackId){return songs.play(trackId);}
}
