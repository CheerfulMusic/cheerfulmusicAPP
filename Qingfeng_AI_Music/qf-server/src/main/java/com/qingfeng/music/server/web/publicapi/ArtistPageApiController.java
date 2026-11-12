package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.artist.ArtistProfileService.PublicProfile;
import com.qingfeng.music.server.web.AudioResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"mysql", "test"})
@RequestMapping("/api/public/artists/{artist}")
public class ArtistPageApiController {
    private final ArtistProfileService profiles;

    public ArtistPageApiController(ArtistProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public ResponseEntity<PublicProfile> profile(@PathVariable String artist) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.publishedProfile(artist));
    }

    @GetMapping("/avatar/{key}")
    public ResponseEntity<byte[]> avatar(@PathVariable String artist, @PathVariable String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
                .body(profiles.publicAvatar(artist, key));
    }

    @GetMapping("/songs/{song}/{version}/audio")
    public ResponseEntity<Resource> audio(@PathVariable String artist, @PathVariable String song,
            @PathVariable long version, @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        return AudioResponse.from(profiles.publicAudio(artist, song, version), false, range);
    }

    @GetMapping("/songs/{song}/{version}/cover")
    public ResponseEntity<Resource> cover(@PathVariable String artist, @PathVariable String song,
            @PathVariable long version) {
        return AudioResponse.from(profiles.publicCover(artist, song, version), false);
    }
}
