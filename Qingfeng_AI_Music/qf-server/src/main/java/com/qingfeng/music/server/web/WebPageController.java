package com.qingfeng.music.server.web;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
/** 本地交付包由同一个进程提供Vue和API，深层页面刷新回到Vue入口。 */
@Controller
public class WebPageController {
    @GetMapping({"/","/create","/works","/works/{id}","/gift/{token}","/account","/recharge"})
    public String index(){return "forward:/index.html";}
}
