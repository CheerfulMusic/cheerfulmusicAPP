package com.qingfeng.music.server.web.app;

import com.qingfeng.music.server.avatar.AvatarGenerationService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app/avatar/generation")
public class AvatarGenerationController {
    private final AvatarGenerationService service;
    public AvatarGenerationController(AvatarGenerationService service){this.service=service;}
    @GetMapping("/latest") public ResponseEntity<AvatarGenerationService.View> latest(@AuthenticationPrincipal CreatorPrincipal creator){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.latest(creator.id()));
    }
}
