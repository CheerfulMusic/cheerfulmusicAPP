package com.qingfeng.music.server.web.app;

import com.qingfeng.music.creation.QuickCreationService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app/works/{work}/quick-creations")
public class QuickCreationController {
    private final QuickCreationService service;
    public QuickCreationController(QuickCreationService service){this.service=service;}
    public record Request(@NotNull @PositiveOrZero Long workVersion,@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}")String requestKey,@NotBlank @Pattern(regexp="zh-CN|en|mixed")String language){}
    @PostMapping public QuickCreationService.View start(@AuthenticationPrincipal CreatorPrincipal owner,@PathVariable String work,@Valid @RequestBody Request input){return service.start(owner.id(),work,input.workVersion(),input.requestKey(),input.language());}
    @GetMapping("/latest") public QuickCreationService.View latest(@AuthenticationPrincipal CreatorPrincipal owner,@PathVariable String work){return service.latest(owner.id(),work).orElse(null);}
    @GetMapping("/{id}") public QuickCreationService.View get(@AuthenticationPrincipal CreatorPrincipal owner,@PathVariable String work,@PathVariable String id){return service.get(owner.id(),work,id);}
    @PostMapping("/{id}/retry") public QuickCreationService.View retry(@AuthenticationPrincipal CreatorPrincipal owner,@PathVariable String work,@PathVariable String id){return service.retry(owner.id(),work,id);}
}
