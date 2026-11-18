package com.qingfeng.music.server.web;

import com.qingfeng.music.server.config.FrontendAssetConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FrontendAssetConfigurationTest {
    @TestConfiguration
    @EnableWebMvc
    static class TestMvc {}

    @Test void immutablePolicyOnlyForExistingFingerprintedAssets() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestMvc.class, FrontendAssetConfiguration.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/assets/fixture-1234abcd.js")).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
            mvc.perform(get("/assets/missing-1234abcd.js")).andExpect(status().isNotFound())
                    .andExpect(header().doesNotExist("Cache-Control"));
        }
    }
}
