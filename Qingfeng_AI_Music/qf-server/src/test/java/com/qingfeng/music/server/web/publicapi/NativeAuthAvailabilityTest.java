package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NativeAuthAvailabilityTest {
    @SuppressWarnings("unchecked")
    @Test void providerOutageReturnsRetryableFailureInsteadOfDisabledChannels()throws Exception {
        ObjectProvider<LogtoNativeClient> clients=mock(ObjectProvider.class);
        var client=mock(LogtoNativeClient.class);
        when(clients.getIfAvailable()).thenReturn(client);
        when(client.capabilities()).thenThrow(new IllegalStateException("provider unavailable"));
        var controller=new NativeAuthController(clients,mock(IdentityApi.class),mock(LogtoIdentityService.class),true,true,true,"issuer");
        MockMvcBuilders.standaloneSetup(controller).build().perform(get("/api/public/auth/native/status"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("LOGIN_UNAVAILABLE"));
    }
    @SuppressWarnings("unchecked")
    @Test void hourlyThrottleReportsRemainingHourInsteadOfOneMinute()throws Exception {
        ObjectProvider<LogtoNativeClient> clients=mock(ObjectProvider.class);
        var client=mock(LogtoNativeClient.class);var identity=mock(IdentityApi.class);
        when(clients.getIfAvailable()).thenReturn(client);
        when(identity.resolve(nullable(String.class))).thenReturn(Optional.of("guest"));
        when(client.capabilities()).thenReturn(new LogtoNativeClient.Capabilities(true,true,true,true));
        when(client.begin(anyString())).thenReturn(mock(LogtoNativeClient.Interaction.class));
        when(client.sendCode(any(),anyString(),anyString(),anyString())).thenReturn("verification");
        var controller=new NativeAuthController(clients,identity,mock(LogtoIdentityService.class),true,true,true,"issuer");
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        var field=NativeAuthController.class.getDeclaredField("limits");field.setAccessible(true);
        Map<String,?> limits=(Map<String,?>)field.get(controller);
        for(int i=0;i<5;i++) {
            limits.keySet().removeIf(key->key.startsWith("send:")); // Simulate minute passing; retain hourly buckets.
            mvc.perform(post("/api/public/auth/native/code").contentType("application/json").content("{\"channel\":\"email\",\"identifier\":\"test@example.test\",\"purpose\":\"register\"}"))
                .andExpect(status().isOk());
        }
        var result=mvc.perform(post("/api/public/auth/native/code").contentType("application/json").content("{\"channel\":\"email\",\"identifier\":\"test@example.test\",\"purpose\":\"register\"}"))
            .andExpect(status().isTooManyRequests()).andReturn();
        long retry=Long.parseLong(result.getResponse().getHeader("Retry-After"));
        assertTrue(retry>3500&&retry<=3600,"Expected remaining hourly window, got "+retry);
    }
}
