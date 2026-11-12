package com.qingfeng.music.server.web.app;

import com.qingfeng.music.server.avatar.AvatarService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @Profile({"mysql", "test"}) @RequestMapping("/api/app/avatar")
public class AvatarController {
    private final AvatarService avatars;
    public AvatarController(AvatarService avatars) { this.avatars = avatars; }
    @GetMapping public ResponseEntity<AvatarService.View> current(@AuthenticationPrincipal CreatorPrincipal creator) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(avatars.current(creator.id()));
    }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public AvatarService.View upload(@AuthenticationPrincipal CreatorPrincipal creator,@RequestParam("file") MultipartFile file,@RequestParam("expectedCreatorId") String expectedCreatorId) throws IOException {
        if (!creator.id().equals(expectedCreatorId)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"账号已切换，请重新选择头像");
        try (var in = file.getInputStream()) { return avatars.save(creator.id(),in); }
    }
    @GetMapping("/image") public ResponseEntity<InputStreamResource> image(@AuthenticationPrincipal CreatorPrincipal creator) throws IOException {
        var image = avatars.image(creator.id());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).contentLength(image.size()).body(new InputStreamResource(avatars.open(image)));
    }
}
