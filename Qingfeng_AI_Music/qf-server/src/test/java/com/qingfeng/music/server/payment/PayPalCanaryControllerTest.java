package com.qingfeng.music.server.payment;

import com.qingfeng.music.server.security.CreatorPrincipal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Checks principal resolution at the HTTP boundary, with no real provider or database. */
class PayPalCanaryControllerTest {
    @Test void requestOwnerParametersCannotReplaceTheAuthenticatedOwner() throws Exception {
        var service=mock(PayPalPaymentService.class);
        var principal=new CreatorPrincipal("00000000-0000-0000-0000-000000000001");
        when(service.catalog(any())).thenReturn(Map.of("enabled",false));
        when(service.create(anyString(),anyString())).thenReturn(Map.of("orderId","synthetic"));
        var mvc=MockMvcBuilders.standaloneSetup(new PayPalPaymentController(service))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        var prior=SecurityContextHolder.getContext();
        try {
            var context=SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal,"unused",java.util.List.of()));
            SecurityContextHolder.setContext(context);
            mvc.perform(get("/api/app/paypal/catalog").param("owner","forged").param("principal","forged"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
            mvc.perform(post("/api/app/paypal/orders").contentType("application/json")
                .content("{\"packageId\":\"starter-USD\",\"owner\":\"forged\"}"))
                .andExpect(status().isOk());
            verify(service).catalog(principal);
            verify(service).create(principal.id(),"starter-USD");
            verify(service,never()).create(eq("forged"),anyString());
        } finally {SecurityContextHolder.setContext(prior);}
    }
}
