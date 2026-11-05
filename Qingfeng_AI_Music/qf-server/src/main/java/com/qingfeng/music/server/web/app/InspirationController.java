package com.qingfeng.music.server.web.app;

import com.qingfeng.music.lyrics.application.InspirationRewriteService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql", "test"})
@RequestMapping("/api/app/inspiration")
public class InspirationController {
    private final InspirationRewriteService service;
    public InspirationController(InspirationRewriteService service) { this.service = service; }
    public record RewriteRequest(@NotBlank @Size(max=4000) String text, @NotBlank @Pattern(regexp="zh-CN|en|mixed") String language) {}
    public record RewriteResponse(String text) {}
    @PostMapping("/rewrite")
    public RewriteResponse rewrite(@AuthenticationPrincipal CreatorPrincipal creator, @Valid @RequestBody RewriteRequest request) {
        return new RewriteResponse(service.rewrite(creator.id(), request.text(), request.language()));
    }
}
