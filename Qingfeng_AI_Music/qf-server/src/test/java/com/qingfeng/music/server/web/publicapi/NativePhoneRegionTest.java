package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.LogtoIdentityService;
import com.qingfeng.music.server.security.LogtoNativeClient;
import com.qingfeng.music.server.security.SmsDestinationPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NativePhoneRegionTest {
    @SuppressWarnings("unchecked")
    @Test void validButAmbiguousIsleOfManNumberGetsRegionErrorBeforeProvider() throws Exception {
        var provider=(ObjectProvider<LogtoNativeClient>)mock(ObjectProvider.class);
        var identity=mock(IdentityApi.class);
        var policy=new SmsDestinationPolicy(true,true,true,false,true,"GB,JE");
        var controller=new NativeAuthController(provider,identity,mock(LogtoIdentityService.class),policy,true,true,true,"issuer");
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/public/auth/native/code").contentType("application/json")
            .content("{\"channel\":\"phone\",\"purpose\":\"sign-in\",\"identifier\":\"+447924123456\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LOGIN_REGION_UNAVAILABLE"));
        mvc.perform(post("/api/public/auth/native/code").contentType("application/json")
            .content("{\"channel\":\"phone\",\"purpose\":\"sign-in\",\"identifier\":\"+447700900123\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LOGIN_INPUT"));
        verifyNoInteractions(provider,identity);
    }
    @Test void unsupportedInternationalRegionFailsBeforeProviderOrCreatorLookup() {
        @SuppressWarnings("unchecked") var provider=(ObjectProvider<LogtoNativeClient>)mock(ObjectProvider.class);
        var identity=mock(IdentityApi.class);
        var controller=new NativeAuthController(provider,identity,mock(LogtoIdentityService.class),new SmsDestinationPolicy(true,false,true,false,true,"GB"),true,true,false,"https://auth.cheerfulmusic.com/oidc");
        var input=new NativeAuthController.CodeRequest();input.channel="phone";input.identifier="+447400123123";input.purpose="sign-in";
        var failure=assertThrows(RuntimeException.class,()->controller.sendCode(input,new MockHttpServletRequest()));
        assertEquals("当前暂不支持向该地区发送验证码",failure.getMessage());
        verifyNoInteractions(provider,identity);
    }
}
