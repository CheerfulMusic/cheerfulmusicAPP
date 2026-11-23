package com.qingfeng.music.wxapi;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import com.qingfeng.music.MainActivity;
import com.qingfeng.music.WechatMusic;
import com.qingfeng.music.WechatMusicPlugin;
import com.tencent.mm.opensdk.modelbase.BaseReq;
import com.tencent.mm.opensdk.modelbase.BaseResp;
import com.tencent.mm.opensdk.modelmsg.ShowMessageFromWX;
import com.tencent.mm.opensdk.openapi.IWXAPI;
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler;

public class WXEntryActivity extends Activity implements IWXAPIEventHandler {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        IWXAPI api = WechatMusicPlugin.registeredApi(this);
        if (api == null || !api.handleIntent(intent, this)) finish();
    }

    @Override
    public void onReq(BaseReq request) {
        if (request instanceof ShowMessageFromWX.Req) {
            ShowMessageFromWX.Req show = (ShowMessageFromWX.Req) request;
            if (show.message != null && WechatMusic.isSongId(show.message.messageExt)) {
                Intent open = new Intent(this, MainActivity.class);
                open.putExtra(WechatMusic.OPENED_SONG, show.message.messageExt);
                open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(open);
            }
        }
        finish();
    }

    @Override
    public void onResp(BaseResp response) { finish(); }
}
