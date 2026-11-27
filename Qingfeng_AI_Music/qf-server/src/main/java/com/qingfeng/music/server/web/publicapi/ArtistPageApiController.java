package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.artist.ArtistProfileService.PublicProfile;
import com.qingfeng.music.server.web.AudioResponse;
import com.qingfeng.music.server.web.MediaCdnSigner;
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
    private final MediaCdnSigner signer;

    public ArtistPageApiController(ArtistProfileService profiles, MediaCdnSigner signer) {
        this.profiles = profiles;
        this.signer = signer;
    }

    @GetMapping
    public ResponseEntity<PublicProfile> profile(@PathVariable String artist) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.publishedProfile(artist));
    }

    @GetMapping("/avatar/{key}")
    public ResponseEntity<byte[]> avatar(@PathVariable String artist, @PathVariable String key) {
        return jpeg(profiles.publicAvatar(artist, key));
    }

    @GetMapping("/cover/{key}")
    public ResponseEntity<byte[]> cover(@PathVariable String artist, @PathVariable String key) {
        return jpeg(profiles.publicProfileCover(artist, key));
    }

    @GetMapping("/songs/{song}/{version}/lyrics")
    public ResponseEntity<com.qingfeng.music.lyrics.playback.PlaybackLyrics.Snapshot> lyrics(
            @PathVariable String artist, @PathVariable String song, @PathVariable long version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(profiles.publicPlaybackLyrics(artist, song, version));
    }

    @GetMapping("/songs/{song}/{version}/audio")
    public ResponseEntity<Resource> audio(@PathVariable String artist, @PathVariable String song,
            @PathVariable long version, @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        if (signer.enabled()) {
            try {
                var location = signer.audio(profiles.publicAudioRef(artist, song, version).storageKey());
                if (location.isPresent()) return AudioResponse.redirect(location.get());
            } catch (RuntimeException ignored) {
                // 回退时重新校验歌曲公开状态及版本。
            }
        }
        return AudioResponse.from(profiles.publicAudio(artist, song, version), false, range);
    }

    @GetMapping("/songs/{song}/{version}/cover")
    public ResponseEntity<Resource> cover(@PathVariable String artist, @PathVariable String song,
            @PathVariable long version) {
        if (signer.enabled()) {
            var location = signer.cover(profiles.publicCoverRef(artist, song, version).storageKey());
            if (location.isPresent()) return AudioResponse.redirect(location.get());
        }
        return AudioResponse.cover(profiles.publicCover(artist, song, version));
    }

    private static ResponseEntity<byte[]> jpeg(byte[] body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(body);
    }
}
