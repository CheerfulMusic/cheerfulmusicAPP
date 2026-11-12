package com.qingfeng.music.server.web.app;

import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.artist.ArtistProfileService.MyProfile;
import com.qingfeng.music.server.artist.ArtistProfileService.Release;
import com.qingfeng.music.server.artist.ArtistProfileService.ReleaseSelection;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile({"mysql", "test"})
@RequestMapping("/api/app/artist-profile")
public class ArtistProfileController {
    private final ArtistProfileService profiles;

    public ArtistProfileController(ArtistProfileService profiles) {
        this.profiles = profiles;
    }

    public record Visibility(@NotNull Boolean published) {}
    public record SongRequest(@NotBlank @Size(max = 36) String assetId, @Size(max = 36) String coverId,
                              @NotBlank @Size(max = 100) String title) {}

    @GetMapping
    public ResponseEntity<MyProfile> mine(@AuthenticationPrincipal CreatorPrincipal principal) {
        return noStore(profiles.mine(account(principal)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MyProfile> save(@AuthenticationPrincipal CreatorPrincipal principal,
            @RequestParam String stageName, @RequestParam String tagline,
            @RequestParam(defaultValue = "false") boolean removeAvatar,
            @RequestParam(required = false) MultipartFile avatar) {
        return noStore(profiles.save(account(principal), stageName, tagline, avatar, removeAvatar));
    }

    @PutMapping("/visibility")
    public ResponseEntity<MyProfile> visibility(@AuthenticationPrincipal CreatorPrincipal principal,
            @Valid @RequestBody Visibility request) {
        return noStore(profiles.setPublished(account(principal), request.published()));
    }

    @GetMapping("/avatar/{key}")
    public ResponseEntity<byte[]> avatar(@AuthenticationPrincipal CreatorPrincipal principal, @PathVariable String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
                .body(profiles.ownerAvatar(account(principal), key));
    }

    @GetMapping("/releases/{work}")
    public ResponseEntity<ReleaseSelection> selection(@AuthenticationPrincipal CreatorPrincipal principal,
            @PathVariable String work) {
        return noStore(profiles.selection(account(principal), work));
    }

    @PutMapping("/releases/{work}")
    public ResponseEntity<Release> publish(@AuthenticationPrincipal CreatorPrincipal principal, @PathVariable String work,
            @Valid @RequestBody SongRequest song) {
        return noStore(profiles.publishSong(account(principal), work, song.assetId(), song.coverId(), song.title()));
    }

    @DeleteMapping("/releases/{work}")
    public ResponseEntity<MyProfile> remove(@AuthenticationPrincipal CreatorPrincipal principal, @PathVariable String work) {
        String creator = account(principal);
        profiles.removeSong(creator, work);
        return noStore(profiles.mine(creator));
    }

    private String account(CreatorPrincipal principal) {
        if (principal == null || !profiles.hasAccount(principal.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请先登录音乐账号。");
        }
        return principal.id();
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
