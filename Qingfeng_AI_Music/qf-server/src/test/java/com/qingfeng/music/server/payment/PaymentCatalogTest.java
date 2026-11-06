package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class PaymentCatalogTest {
    @Test void publishedPackagesHaveServerControlledCurrencyAndPrice() {
        var catalog=new PaymentCatalog(new MockEnvironment());
        assertEquals(3,catalog.packages().size());
        var usd=catalog.find("starter-USD").orElseThrow();
        assertEquals(new BigDecimal("4.99"),usd.amount());
        assertEquals("USD",usd.currency());
        assertEquals(100,usd.credits());
        assertTrue(catalog.find("starter-EUR").isEmpty());
        assertTrue(catalog.find("starter-GBP").isEmpty());
        assertTrue(catalog.find("starter-GBP-0.01").isEmpty());
    }
    @Test void invalidPriceFailsClosedAndConfiguredPriceIsUsed() {
        var env=new MockEnvironment().withProperty("qingfeng.payments.prices.usd.starter","6.50");
        assertEquals(new BigDecimal("6.50"),new PaymentCatalog(env).find("starter-USD").orElseThrow().amount());
        env.setProperty("qingfeng.payments.prices.usd.starter","-1");
        assertThrows(IllegalArgumentException.class,()->new PaymentCatalog(env));
        env.setProperty("qingfeng.payments.prices.usd.starter","1.001");
        assertThrows(IllegalArgumentException.class,()->new PaymentCatalog(env));
    }
}
