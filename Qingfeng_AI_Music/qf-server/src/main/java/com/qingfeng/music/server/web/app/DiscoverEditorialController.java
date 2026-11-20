package com.qingfeng.music.server.web.app;

import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile({"mysql", "test"})
@RequestMapping("/api/app/discover/editor")
public class DiscoverEditorialController {
    private final ArtistProfileService profiles;
    private final Set<String> editors;

    public DiscoverEditorialController(ArtistProfileService profiles,
            @Value("${qingfeng.discover.editor-owner-ids:}") String owners) {
        this.profiles = profiles;
        this.editors = Arrays.stream(owners.split(",")).map(String::trim).filter(owner -> !owner.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public record FeatureRequest(@NotNull Boolean featured) {}
    public record VocalRequest(@NotNull Boolean approved) {}

    @PutMapping("/songs/{song}")
    public ResponseEntity<Void> feature(@AuthenticationPrincipal CreatorPrincipal principal,
            @PathVariable UUID song, @Valid @RequestBody FeatureRequest request) {
        requireEditor(principal);
        profiles.setDiscoverFeatured(song.toString(), request.featured());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/songs/{song}/{version}/vocal")
    public ResponseEntity<Void> vocal(@AuthenticationPrincipal CreatorPrincipal principal,
            @PathVariable UUID song, @PathVariable @jakarta.validation.constraints.Positive long version,
            @Valid @RequestBody VocalRequest request) {
        requireEditor(principal);
        profiles.setDiscoverVocal(song.toString(), version, request.approved());
        return ResponseEntity.noContent().build();
    }

    private void requireEditor(CreatorPrincipal principal) {
        if (principal == null || !editors.contains(principal.id()) || !profiles.hasAccount(principal.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
