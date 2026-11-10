package com.qingfeng.music.server.web.app;

import com.qingfeng.music.server.analytics.AnalyticsDataErasureService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 端点授权边界：未登录 401；creator_id 只认服务端会话，端点不接受任何身份参数。 */
class AnalyticsDataControllerTest {

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private AnalyticsDataController controller(AnalyticsDataErasureService service) {
        return new AnalyticsDataController(service);
    }

    private static void login(String creatorId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CreatorPrincipal(creatorId), null, List.of(new SimpleGrantedAuthority("ROLE_CREATOR"))));
    }

    @Test
    void unauthenticatedIsRejectedBeforeTouchingService() {
        var service = mock(AnalyticsDataErasureService.class);
        var response = controller(service).eraseMine();
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("LOGIN_REQUIRED", response.getBody().get("error"));
        verifyNoInteractions(service);
    }

    @Test
    void usesOnlySessionCreatorId() {
        var service = mock(AnalyticsDataErasureService.class);
        when(service.erase("creator-1")).thenReturn(
                new AnalyticsDataErasureService.Result(AnalyticsDataErasureService.Status.DISABLED,
                        "req-1", "DELETE", 0, 0, "off"));
        login("creator-1");
        var response = controller(service).eraseMine();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("DISABLED", response.getBody().get("status"));
        verify(service).erase("creator-1");
        verifyNoMoreInteractions(service);
    }

    @Test
    void endpointExposesNoClientSuppliedIdentityParameter() throws Exception {
        // 端点方法签名不得含任何参数，从结构上杜绝「客户端传 creatorId 删他人数据」。
        var method = AnalyticsDataController.class.getDeclaredMethod("eraseMine");
        assertEquals(0, method.getParameterCount(),
                "擦除端点不接受任何请求参数，creator_id 只能来自服务端会话");
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertNotNull(AnalyticsDataController.class.getAnnotation(
                org.springframework.web.bind.annotation.RestController.class));
    }
}
