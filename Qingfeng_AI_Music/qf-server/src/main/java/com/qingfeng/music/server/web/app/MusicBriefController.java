package com.qingfeng.music.server.web.app;

import com.qingfeng.music.settings.MusicBriefService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app/music-settings/simplify")
public class MusicBriefController {
    private final MusicBriefService service;
    public MusicBriefController(MusicBriefService service){this.service=service;}
    public record Status(boolean available){}
    public record Input(@NotBlank @Size(max=300) String text){}
    public record Suggestion(String original,String text){}
    @GetMapping public Status status(){return new Status(service.available());}
    @PostMapping public Suggestion suggest(@AuthenticationPrincipal CreatorPrincipal creator,@Valid @RequestBody Input input){return new Suggestion(input.text(),service.suggest(creator.id(),input.text()));}
}
