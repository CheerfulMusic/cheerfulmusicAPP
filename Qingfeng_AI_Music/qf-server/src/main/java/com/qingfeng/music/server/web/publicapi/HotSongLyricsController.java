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
    public ResponseEntity<PlaybackLyrics.Snapshot> lyrics(@PathVariable String track,
            @RequestParam(name="audioHash",required=false) String audioHash) {
        var song=RecommendationSamples.SONGS.stream().filter(item->item.id().equals(track)).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(audioHash!=null){
            String key=RecommendationSamples.objectKey(song.audio().substring("/media/".length())).orElseThrow();
            if(!audioHash.matches("[a-f0-9]{12}")||!key.contains("/audio/"+audioHash+"/"))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preparation.sample(track));
    }
}
