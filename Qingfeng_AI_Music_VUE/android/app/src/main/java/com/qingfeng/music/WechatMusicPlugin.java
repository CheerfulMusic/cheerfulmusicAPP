package com.qingfeng.music;

import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.tencent.mm.opensdk.constants.Build;
import com.tencent.mm.opensdk.modelmsg.SendMessageToWX;
import com.tencent.mm.opensdk.openapi.IWXAPI;
import com.tencent.mm.opensdk.openapi.WXAPIFactory;

@CapacitorPlugin(name = "WechatMusic")
public class WechatMusicPlugin extends Plugin {
    public static IWXAPI registeredApi(Context context) {
        String appId = BuildConfig.WECHAT_MOBILE_APP_ID;
        if (appId.isEmpty()) return null;
        IWXAPI api = WXAPIFactory.createWXAPI(context.getApplicationContext(), appId, true);
        return api.registerApp(appId) ? api : null;
    }

    @PluginMethod
    public void availability(PluginCall call) {
        IWXAPI api = registeredApi(getContext());
        boolean installed = api != null && api.isWXAppInstalled();
        JSObject result = new JSObject();
        result.put("configured", api != null);
        result.put("installed", installed);
        result.put("supported", installed && api.getWXAppSupportAPI() >= Build.SUPPORT_SEND_MUSIC_VIDEO_MESSAGE);
        call.resolve(result);
    }

    @PluginMethod
    public void share(PluginCall call) {
        IWXAPI api = registeredApi(getContext());
        if (api == null || !api.isWXAppInstalled() || api.getWXAppSupportAPI() < Build.SUPPORT_SEND_MUSIC_VIDEO_MESSAGE) {
            call.reject("请检查移动应用配置及微信版本。");
            return;
        }
        try {
            String encoded = call.getString("thumbnailBase64", "");
            if (encoded.length() > 43692) throw new IllegalArgumentException("歌曲缩略图过大。");
            SendMessageToWX.Req request = WechatMusic.message(call.getString("songId"), call.getString("title"),
                    call.getString("singer"), call.getInt("durationMs", 0), call.getString("destination"),
                    Base64.decode(encoded, Base64.DEFAULT), call.getString("lrc"));
            if (!request.checkArgs() || !api.sendReq(request)) { call.reject("微信未接收此次音乐分享。"); return; }
            call.resolve();
        } catch (IllegalArgumentException e) { call.reject("音乐卡片资料无效。", e); }
    }

    @PluginMethod
    public void consumeOpenedSong(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            Intent intent = getActivity().getIntent();
            String id = intent.getStringExtra(WechatMusic.OPENED_SONG);
            intent.removeExtra(WechatMusic.OPENED_SONG);
            JSObject result = new JSObject();
            if (WechatMusic.isSongId(id)) result.put("songId", id);
            call.resolve(result);
        });
    }
}
