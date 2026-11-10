package com.qingfeng.music.server.web.app;

import com.qingfeng.music.server.analytics.AnalyticsDataErasureService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录用户撤回同意后，删除/匿名化自己的历史埋点数据（隐私治理方案 §2.B.2）。
 *
 * <p>授权边界（fail-closed）：
 * <ul>
 *   <li><b>端点不接受任何身份参数</b>：没有 @RequestParam / @PathVariable / @RequestBody，
 *       creator_id 只从服务端登录态 {@link CreatorPrincipal} 解析，客户端无法指定或伪造他人标识。</li>
 *   <li>未登录一律 401，不返回任何数据状态信息。</li>
 *   <li>服务层还叠加独立开关（默认关）、方式配置（法务 L3 未定则拒绝）与
 *       {@code WHERE creator_id = ?} 严格范围。</li>
 *   <li>匿名用户<b>不开放</b>公开删除 API：visitor_id 在 localStorage，不是身份凭证，
 *       走隐私政策联系邮箱的人工申请流程。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/app/analytics")
@Profile({"mysql", "test"})
public class AnalyticsDataController {

    private final AnalyticsDataErasureService erasure;

    public AnalyticsDataController(AnalyticsDataErasureService erasure) { this.erasure = erasure; }

    @DeleteMapping("/data")
    public ResponseEntity<Map<String, Object>> eraseMine() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CreatorPrincipal principal)
                || principal.id() == null || principal.id().isBlank()) {
            return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).body(Map.of("error", "LOGIN_REQUIRED"));
        }
        var result = erasure.erase(principal.id());
        var body = new LinkedHashMap<String, Object>();
        body.put("status", result.status().name());
        body.put("method", result.method());
        body.put("matched", result.matched());
        body.put("affected", result.affected());
        body.put("reason", result.reason());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
