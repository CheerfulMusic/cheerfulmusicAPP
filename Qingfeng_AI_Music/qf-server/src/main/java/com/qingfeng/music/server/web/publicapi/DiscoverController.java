package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.artist.ArtistProfileService.DiscoverPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"mysql", "test"})
@RequestMapping("/api/public/discover")
public class DiscoverController {
    private final ArtistProfileService profiles;

    public DiscoverController(ArtistProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public ResponseEntity<DiscoverPage> list(
            @RequestParam(defaultValue = "false") boolean featured,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.discover(featured, offset));
    }
}
