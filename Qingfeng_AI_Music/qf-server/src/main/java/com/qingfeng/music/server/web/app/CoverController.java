package com.qingfeng.music.server.web.app;
import com.qingfeng.music.cover.api.CoverApi;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.web.AudioResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app/works/{work}/covers")
public class CoverController {
 private final CoverApi covers;public CoverController(CoverApi covers){this.covers=covers;}
 @GetMapping public CoverApi.State state(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work){return covers.state(creator.id(),work);}
 @PostMapping(consumes="multipart/form-data") public CoverApi.Asset upload(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@RequestParam("file")MultipartFile file)throws IOException{try(var in=file.getInputStream()){return covers.upload(creator.id(),work,in);}}
 public record Selection(@Pattern(regexp="[0-9a-fA-F-]{36}")String coverId,@PositiveOrZero long expectedVersion){}
 @PutMapping("/selection") public CoverApi.State select(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody Selection selection){return covers.select(creator.id(),work,selection.coverId(),selection.expectedVersion());}
 @GetMapping("/{cover}") public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> image(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@PathVariable String cover){return AudioResponse.from(covers.open(creator.id(),work,cover),false);}
 public record Edit(@NotNull @Pattern(regexp="[0-9a-fA-F-]{36}")String sourceId,@NotBlank @Size(max=1000)String prompt,@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}")String requestKey){}
 @PostMapping("/edits") public CoverApi.Edit edit(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody Edit edit){return covers.edit(creator.id(),work,edit.sourceId(),edit.prompt(),edit.requestKey());}
}
