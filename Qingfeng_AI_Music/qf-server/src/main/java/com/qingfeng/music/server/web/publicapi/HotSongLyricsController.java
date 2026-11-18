package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.lyrics.playback.PlaybackLyrics;
import com.qingfeng.music.server.hotsong.RecommendationSamples;
import com.qingfeng.music.server.lyrics.LyricsPreparation;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/public/hot-songs")
public class HotSongLyricsController {
    private final LyricsPreparation preparation;
    public HotSongLyricsController(LyricsPreparation preparation){this.preparation=preparation;}
    @GetMapping("/{track}/lyrics")
    public ResponseEntity<PlaybackLyrics.Snapshot> lyrics(@PathVariable String track) {
        if(RecommendationSamples.SONGS.stream().noneMatch(song->song.id().equals(track)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preparation.sample(track));
    }
}
