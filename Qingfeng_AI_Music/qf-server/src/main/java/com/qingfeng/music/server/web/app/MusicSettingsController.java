package com.qingfeng.music.server.web.app;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app")
public class MusicSettingsController {
 private final MusicSettings settings;public MusicSettingsController(MusicSettings settings){this.settings=settings;}
 public record Save(long expectedVersion,MusicSettings.Preferences preferences){}
 @GetMapping("/music-settings/catalog") public MusicSettings.Catalog catalog(){return settings.catalog();}
 @PostMapping("/music-settings/preview") public MusicSettings.Direction preview(@RequestBody MusicSettings.Preferences preferences){return settings.preview(preferences);}
 @GetMapping("/works/{work}/music-settings") public MusicSettings.View get(@AuthenticationPrincipal CreatorPrincipal user,@PathVariable String work){return settings.get(user.id(),work);}
 @PutMapping("/works/{work}/music-settings") public MusicSettings.View save(@AuthenticationPrincipal CreatorPrincipal user,@PathVariable String work,@RequestBody Save input){return settings.save(user.id(),work,input.expectedVersion(),input.preferences());}
 @GetMapping("/works/{work}/generations/{task}/music-settings") public MusicSettings.Direction task(@AuthenticationPrincipal CreatorPrincipal user,@PathVariable String work,@PathVariable String task){return settings.task(user.id(),work,task).orElse(null);}
}
