package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.artist.DiscoverSongService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.security.CreatorSessionFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"mysql", "test"})
public class DiscoverSongController {
    private static final String PUBLIC = "/api/public/discover/songs/{song}/{version}";
    private static final String ACCOUNT = "/api/app/discover/songs/{song}/{version}";
    private final DiscoverSongService songs;
    private final IdentityApi identity;
    private final ArtistProfileService profiles;

    public DiscoverSongController(DiscoverSongService songs, IdentityApi identity, ArtistProfileService profiles) {
        this.songs = songs;
        this.identity = identity;
        this.profiles = profiles;
    }

    public record Like(@NotNull Boolean active) {}
    public record CommentText(@NotNull @Size(min = 1, max = 400) String text) {}
    public record DescriptionText(@NotNull @Size(max = 500) String text) {}

    @GetMapping(PUBLIC)
    public ResponseEntity<DiscoverSongService.Detail> detail(@PathVariable UUID song, @PathVariable @Positive long version,
                                                            HttpServletRequest request) {
        return noStore(songs.detail(song.toString(), version, viewer(request)));
    }

    @GetMapping(value = PUBLIC + "/thumbnail.png", produces = "image/png")
    public ResponseEntity<byte[]> thumbnail(@PathVariable UUID song, @PathVariable @Positive long version) throws IOException {
        String songId = song.toString();
        var release = songs.sharingRelease(songId, version);
        byte[] thumbnail = null;
        if (release.song().hasCover()) {
            try {
                var cover = profiles.publicCover(release.artistId(), songId, version);
                songs.sharingRelease(songId, version);
                try (var source = cover.source().open(0)) {
                    thumbnail = PublicSongController.thumbnailBytes(source);
                }
            } catch (DomainException missing) {
                if (!"COVER_NOT_FOUND".equals(missing.code())) throw missing;
            } catch (IOException unreadable) {
                // Use the default artwork when the cover cannot be decoded.
            }
        }
        if (thumbnail == null) thumbnail = new ClassPathResource("static/brand/share-default-cover.png").getContentAsByteArray();
        return noStore(thumbnail);
    }

    @GetMapping(PUBLIC + "/comments")
    public ResponseEntity<DiscoverSongService.Comments> comments(@PathVariable UUID song, @PathVariable @Positive long version,
            @RequestParam(defaultValue = "") @Size(max = 100) String after, HttpServletRequest request) {
        return noStore(songs.comments(song.toString(), version, viewer(request), after));
    }

    @PutMapping(ACCOUNT + "/like")
    public ResponseEntity<DiscoverSongService.Likes> like(@PathVariable UUID song, @PathVariable @Positive long version,
            @AuthenticationPrincipal CreatorPrincipal viewer, @Valid @RequestBody Like input) {
        return noStore(songs.like(song.toString(), version, viewer.id(), input.active()));
    }

    @PutMapping(ACCOUNT + "/comments/{comment}")
    public ResponseEntity<DiscoverSongService.PostedComment> postComment(@PathVariable UUID song, @PathVariable @Positive long version,
            @PathVariable UUID comment, @AuthenticationPrincipal CreatorPrincipal viewer, @Valid @RequestBody CommentText input) {
        return noStore(songs.postComment(song.toString(), version, viewer.id(), comment.toString(), input.text()));
    }

    @DeleteMapping(ACCOUNT + "/comments/{comment}")
    public ResponseEntity<DiscoverSongService.CommentCount> deleteComment(@PathVariable UUID song, @PathVariable @Positive long version,
            @PathVariable UUID comment, @AuthenticationPrincipal CreatorPrincipal viewer) {
        return noStore(songs.deleteComment(song.toString(), version, viewer.id(), comment.toString()));
    }

    @PutMapping(ACCOUNT + "/description")
    public ResponseEntity<DiscoverSongService.Description> description(@PathVariable UUID song, @PathVariable @Positive long version,
            @AuthenticationPrincipal CreatorPrincipal viewer, @Valid @RequestBody DescriptionText input) {
        return noStore(songs.description(song.toString(), version, viewer.id(), input.text()));
    }

    private String viewer(HttpServletRequest request) {
        return identity.resolve(CreatorSessionFilter.token(request)).orElse(null);
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
