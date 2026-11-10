package com.qingfeng.music.server.web.app;

import com.qingfeng.music.server.hotsong.HotSongService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 点赞与收藏必须已登录；评论只读计数，没有写入口。 */
@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app/hot-songs")
public class HotSongReactionController {
    private final HotSongService songs;
    public HotSongReactionController(HotSongService songs){this.songs=songs;}

    @GetMapping("/reactions") public HotSongService.Reactions mine(@AuthenticationPrincipal CreatorPrincipal creator){return songs.reactions(creator.id());}

    @PostMapping("/{trackId}/{kind}") public HotSongService.Toggle add(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String trackId,@PathVariable String kind){return songs.react(creator.id(),trackId,kind,true);}

    @DeleteMapping("/{trackId}/{kind}") public HotSongService.Toggle remove(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String trackId,@PathVariable String kind){return songs.react(creator.id(),trackId,kind,false);}
}
