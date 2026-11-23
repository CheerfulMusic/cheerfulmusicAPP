package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.hotsong.HotSongCreationReferences;
import com.qingfeng.music.server.hotsong.HotSongService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 首页热歌推荐的公开读取与播放计次；播放不需要登录，也不返回任何个人数据。 */
@RestController @Profile({"mysql","test"}) @RequestMapping("/api/public/hot-songs")
public class HotSongController {
    private final HotSongService songs;
    private final ArtistProfileService artists;
    public HotSongController(HotSongService songs, ArtistProfileService artists) {
        this.songs = songs;
        this.artists = artists;
    }

    @GetMapping public Map<String,Object> list(){return Map.of("songs",songs.stats());}

    @GetMapping("/{trackId}/creation-reference")
    public HotSongCreationReferences.Reference creationReference(@PathVariable String trackId) {
        if (HotSongCreationReferences.isPublishedSelection(trackId)) {
            return artists.publishedProfile(HotSongCreationReferences.HOUSE_ARTIST).songs().stream()
                .filter(song -> song.id().equals(trackId))
                .findFirst().map(song -> HotSongCreationReferences.forPublishedSelection(trackId, song.title()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return HotSongCreationReferences.find(trackId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/{trackId}/play") public HotSongService.Stat play(@PathVariable String trackId){return songs.play(trackId);}
}
