package com.qingfeng.music.server.payment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/** Guards the production Live opt-in: real charges must never be reachable from local-only settings. */
class PayPalPublicLiveTest {

    private static PayPalPaymentService service(MockEnvironment env) {
        PayPalPaymentService service = new PayPalPaymentService(null, null, null, new PaymentCatalog(env), env);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "clientId", "CLIENT");
        ReflectionTestUtils.setField(service, "clientSecret", "SECRET");
        ReflectionTestUtils.setField(service, "merchantId", "MERCHANT");
        ReflectionTestUtils.setField(service, "environment", "live");
        ReflectionTestUtils.setField(service, "liveEnabled", true);
        ReflectionTestUtils.setField(service, "webhookId", "WH-123");
        ReflectionTestUtils.setField(service, "returnBase", "https://cheerfulmusic.com/account");
        ReflectionTestUtils.setField(service, "publicLiveEnabled", true);
        return service;
    }

    private static MockEnvironment productionEnv() {
        MockEnvironment env = new MockEnvironment().withProperty("qingfeng.deployment.environment", "production");
        env.setActiveProfiles("mysql");
        return env;
    }

    @Test
    void publicLiveRequiresTheExplicitSecondOptIn() {
        assertTrue(service(productionEnv()).publicLive());
        PayPalPaymentService disabled = service(productionEnv());
        ReflectionTestUtils.setField(disabled, "publicLiveEnabled", false);
        assertFalse(disabled.publicLive());
        assertFalse(disabled.ready());
        assertFalse(disabled.localLive());
    }

    @Test
    void publicLiveIsReadyOnlyWithAllProductionGuards() {
        PayPalPaymentService service = service(productionEnv());
        assertTrue(service.publicLive());
        assertTrue(service.ready());
        assertTrue(service.catalog().get("enabled").equals(true));
        assertTrue(service.catalog().get("environment").equals("live"));
        assertTrue(service.sdkConfig().get("environment").equals("production"));
    }

    @Test
    void localPaypalLiveProfileCannotEnablePublicCharges() {
        MockEnvironment env = new MockEnvironment().withProperty("qingfeng.deployment.environment", "production");
        env.setActiveProfiles("mysql", "paypal-live");
        PayPalPaymentService service = service(env);
        assertFalse(service.publicLive());
        assertFalse(service.ready());
    }

    @Test
    void localDeploymentMarkerCannotEnablePublicCharges() {
        MockEnvironment env = new MockEnvironment().withProperty("qingfeng.deployment.environment", "paypal-local");
        env.setActiveProfiles("mysql");
        assertFalse(service(env).publicLive());
    }

    @Test
    void localReturnUrlCannotEnablePublicCharges() {
        PayPalPaymentService service = service(productionEnv());
        ReflectionTestUtils.setField(service, "returnBase", "http://127.0.0.1:5173/account");
        assertFalse(service.publicLive());
        assertFalse(service.ready());
    }

    @Test
    void publicLiveRequiresWebhookVerification() {
        PayPalPaymentService service = service(productionEnv());
        ReflectionTestUtils.setField(service, "webhookId", "");
        assertFalse(service.publicLive());
        assertFalse(service.ready());
    }

    @Test
    void sandboxAndLocalPopupPathsStillWork() {
        PayPalPaymentService sandbox = service(productionEnv());
        ReflectionTestUtils.setField(sandbox, "environment", "sandbox");
        ReflectionTestUtils.setField(sandbox, "liveEnabled", false);
        ReflectionTestUtils.setField(sandbox, "publicLiveEnabled", false);
        assertTrue(sandbox.ready());
        assertFalse(sandbox.publicLive());

        MockEnvironment local = new MockEnvironment().withProperty("qingfeng.deployment.environment", "paypal-local");
        local.setActiveProfiles("mysql", "paypal-live");
        PayPalPaymentService localService = service(local);
        ReflectionTestUtils.setField(localService, "returnBase", "http://127.0.0.1:5173/account");
        ReflectionTestUtils.setField(localService, "webhookId", "");
        ReflectionTestUtils.setField(localService, "publicLiveEnabled", false);
        assertTrue(localService.localLive());
        assertTrue(localService.ready());
        assertFalse(localService.publicLive());
    }
}
