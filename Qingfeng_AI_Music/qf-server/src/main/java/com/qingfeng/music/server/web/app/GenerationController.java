package com.qingfeng.music.server.web.app;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app/works/{work}/generations")
public class GenerationController {
 private final GenerationApi generation;
 public GenerationController(GenerationApi generation){this.generation=generation;}
 public record Request(@NotBlank String revisionId,@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}")String requestKey){}
 @PostMapping public GenerationApi.Task submit(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody Request request){return generation.submit(creator.id(),work,request.revisionId(),request.requestKey());}
 @GetMapping public List<GenerationApi.Task> list(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work){return generation.list(creator.id(),work);}
 @PostMapping("/{task}/retry-archive") public void retryArchive(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String task){generation.retryArchive(creator.id(),work,task);}
}
