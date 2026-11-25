package com.qingfeng.music.server.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("(mysql | test) & !sg-reader")
@ConditionalOnProperty(name = "qingfeng.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class PrimarySchedulingConfiguration {}
