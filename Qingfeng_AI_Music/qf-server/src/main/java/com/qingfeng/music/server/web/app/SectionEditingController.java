package com.qingfeng.music.server.web.app;

import com.qingfeng.music.generation.application.*;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.port.MusicProvider.VocalAdjustment;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app/works/{work}")
public class SectionEditingController {
    private final SectionEditingService editing;
    public SectionEditingController(SectionEditingService editing){this.editing=editing;}
    public record Request(@NotBlank String sourceAssetId,@PositiveOrZero long workVersion,@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}")String requestKey,@NotNull Double start,@NotNull Double end,@PositiveOrZero int textStart,@Positive int textEnd,@Size(max=5000)String replacementLyrics,
                          @Pattern(regexp="HIGHER|LOWER|RISING_END|FALLING_END|SOFTER|STRONGER|LONGER_END|MORE_CONNECTED|SPEECH_LIKE") String vocalAdjustment){}
    @GetMapping("/audio/{asset}/section-context") public SectionEditingService.Context context(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String asset){return editing.context(creator.id(),work,asset);}
    @GetMapping("/section-edits") public List<SectionEditRepository.Item> list(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work){return editing.list(creator.id(),work);}
    @PostMapping("/section-edits") public GenerationApi.Task submit(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody Request request){
        var adjustment=request.vocalAdjustment()==null?null:VocalAdjustment.valueOf(request.vocalAdjustment());
        return editing.submit(creator.id(),work,new SectionEditingService.Request(request.sourceAssetId(),request.workVersion(),request.requestKey(),request.start(),request.end(),request.textStart(),request.textEnd(),request.replacementLyrics(),adjustment));
    }
}
