package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.LogtoIdentityService;
import com.qingfeng.music.server.security.LogtoNativeClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativePhoneRegionTest {
    @Test void unsupportedInternationalRegionFailsBeforeProviderOrCreatorLookup() {
        @SuppressWarnings("unchecked") var provider=(ObjectProvider<LogtoNativeClient>)mock(ObjectProvider.class);
        var identity=mock(IdentityApi.class);
        var controller=new NativeAuthController(provider,identity,mock(LogtoIdentityService.class),true,true,false,"https://auth.cheerfulmusic.com/oidc");
        var input=new NativeAuthController.CodeRequest();input.channel="phone";input.identifier="+447700900123";input.purpose="sign-in";
        var failure=assertThrows(RuntimeException.class,()->controller.sendCode(input,new MockHttpServletRequest()));
        assertEquals("当前暂不支持向该地区发送验证码",failure.getMessage());
        verifyNoInteractions(provider,identity);
    }
}
