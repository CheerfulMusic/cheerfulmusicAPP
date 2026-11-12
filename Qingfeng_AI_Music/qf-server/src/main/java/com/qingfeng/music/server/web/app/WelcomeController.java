package com.qingfeng.music.server.web.app;

import com.qingfeng.music.server.security.CreatorPrincipal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Claims only the presentation of a committed welcome grant; never grants credits. */
@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app/welcome")
public class WelcomeController {
    private final JdbcTemplate db;
    public WelcomeController(JdbcTemplate db){this.db=db;}
    public record View(boolean show,long amount) {}
    @PostMapping public ResponseEntity<View> claim(@AuthenticationPrincipal CreatorPrincipal creator) {
        var grants=db.queryForList("SELECT amount FROM credit_ledger WHERE owner_id=? AND entry_type='CREDIT' AND reference_id=? AND amount>0 AND created_at>=? "
            +"AND (EXISTS(SELECT 1 FROM creator_google_identity WHERE creator_id=?) OR EXISTS(SELECT 1 FROM creator_oidc_identity WHERE creator_id=?))",
            creator.id(),"first-login:"+creator.id(),Timestamp.from(Instant.now().minus(Duration.ofMinutes(15))),creator.id(),creator.id());
        boolean show=false;
        if(!grants.isEmpty())try {
            db.update("INSERT INTO welcome_dialog_receipt(creator_id,shown_at) VALUES(?,?)",creator.id(),Timestamp.from(Instant.now()));show=true;
        }catch(DuplicateKeyException alreadyShown){/* Another tab or device has claimed the same introduction. */}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new View(show,show?((Number)grants.get(0).get("amount")).longValue():0));
    }
}
