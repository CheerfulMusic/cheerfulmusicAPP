package com.qingfeng.music.server.payment;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class AirwallexSandboxGatewayTest {
    @Test void rejectsUnsafeLinkIdentifiersAndRedirectHosts() {
        assertEquals("link_1", AirwallexSandboxGateway.validateLink("link_1", "https://checkout.airwallex.com/pay/1").id());
        for (String id : new String[]{"", "../other", "id/other", "id?x=1", "x".repeat(129)})
            assertThrows(ResponseStatusException.class, () -> AirwallexSandboxGateway.validateLink(id, "https://checkout.airwallex.com/pay/1"));
        for (String url : new String[]{"http://airwallex.com", "https://airwallex.com.evil.test/", "https://airwallex.com@evil.test/", "https://evil.test/", ":bad"})
            assertThrows(ResponseStatusException.class, () -> AirwallexSandboxGateway.validateLink("link1", url));
    }
}
