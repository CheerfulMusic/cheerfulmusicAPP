package com.qingfeng.music.server.web.app;

import com.qingfeng.music.server.download.DownloadEntitlements;
import com.qingfeng.music.server.download.SharedDownloadGrants;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.web.AudioResponse;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app/shared-downloads")
public class SharedDownloadsController {
 private final DownloadEntitlements accounts;private final SharedDownloadGrants grants;
 public SharedDownloadsController(DownloadEntitlements accounts,SharedDownloadGrants grants){this.accounts=accounts;this.grants=grants;}
 @GetMapping public List<SharedDownloadGrants.Item> list(@AuthenticationPrincipal CreatorPrincipal principal){return grants.list(accounts.verifiedOwner(principal));}
 @GetMapping("/{id}") public ResponseEntity<Resource> audio(@AuthenticationPrincipal CreatorPrincipal principal,@PathVariable String id,@RequestHeader(value="Range",required=false)String range){return AudioResponse.from(grants.content(accounts.verifiedOwner(principal),id),true,range);}
}
