package com.qingfeng.music.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("(mysql | test) & !sg-reader")
@EnableScheduling
public class PrimarySchedulingConfiguration {}
