package com.qingfeng.music.server.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 接管 Spring Boot 的 /error，去掉默认白标错误页。
 *
 * <p>背景：白名单外的页面路径会被 {@code anyRequest().denyAll()} 拦成 403，未映射路径 404，
 * 服务端异常 500；这些错误一旦落到默认错误页，就会把 "Whitelabel Error Page" 原样返回给
 * 浏览器用户。本类对 /api/** 维持 Boot 默认的 JSON 字段不变（前端错误处理依赖它），
 * 对浏览器导航返回中文提示页。
 *
 * <p>Spring Boot 的 {@code BasicErrorController} 在容器中存在 {@link ErrorController} Bean 时
 * 自动让位，因此不会产生 /error 映射冲突。
 */
@RestController
public class WebErrorController implements ErrorController {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault());

    @RequestMapping("/error")
    public ResponseEntity<?> error(HttpServletRequest request) {
        HttpStatus status = resolveStatus(request);
        String path = resolvePath(request);
        if (path.startsWith("/api/")) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"timestamp\":\"" + STAMP.format(Instant.now())
                            + "\",\"status\":" + status.value()
                            + ",\"error\":\"" + status.getReasonPhrase()
                            + "\",\"path\":\"" + escape(path) + "\"}");
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_HTML)
                .body(page(status, path));
    }

    /** 容器在错误派发时把原始状态码放进 jakarta.servlet.error.status_code。 */
    private static HttpStatus resolveStatus(HttpServletRequest request) {
        Object code = request.getAttribute("jakarta.servlet.error.status_code");
        if (code instanceof Integer value) {
            HttpStatus resolved = HttpStatus.resolve(value);
            if (resolved != null) return resolved;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** 原始请求路径由容器放在 jakarta.servlet.error.request_uri，取它才能区分是接口还是页面。 */
    private static String resolvePath(HttpServletRequest request) {
        Object original = request.getAttribute("jakarta.servlet.error.request_uri");
        if (original != null && !original.toString().isEmpty()) return original.toString();
        return request.getRequestURI();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String page(HttpStatus status, String path) {
        String title = switch (status) {
            case NOT_FOUND -> "页面不存在";
            case FORBIDDEN -> "没有访问权限";
            case UNAUTHORIZED -> "请先登录";
            case TOO_MANY_REQUESTS -> "请求过于频繁";
            default -> "服务暂时不可用";
        };
        String hint = switch (status) {
            case NOT_FOUND -> "你访问的地址可能已下线或输入有误。";
            case FORBIDDEN -> "该页面需要更高的权限，或功能尚未开放。";
            case UNAUTHORIZED -> "登录后即可继续使用。";
            case TOO_MANY_REQUESTS -> "请稍后再试。";
            default -> "我们已记录这次异常，请稍后重试。";
        };
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + status.value() + " " + title + "</title><style>"
                + "*{box-sizing:border-box}body{margin:0;min-height:100vh;display:flex;align-items:center;"
                + "justify-content:center;background:#0f1014;color:#e8e9ee;font-family:system-ui,-apple-system,"
                + "'PingFang SC','Microsoft YaHei',sans-serif}.card{max-width:420px;padding:40px 32px;text-align:center}"
                + ".code{font-size:56px;font-weight:700;letter-spacing:2px;color:#8b7cf6}"
                + "h1{font-size:20px;margin:12px 0 8px}p{color:#9ea3b2;font-size:14px;line-height:1.7;margin:0 0 24px}"
                + "code{font-size:12px;color:#6b7080;word-break:break-all}a{display:inline-block;padding:10px 22px;"
                + "border-radius:8px;background:#8b7cf6;color:#fff;text-decoration:none;font-size:14px}"
                + "</style></head><body><div class=\"card\"><div class=\"code\">" + status.value() + "</div>"
                + "<h1>" + title + "</h1><p>" + hint + "</p><p><code>" + escape(path) + "</code></p>"
                + "<a href=\"/\">返回首页</a></div></body></html>";
    }
}
