package com.financialhelper.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class BusinessTimeConfig {

    @Bean
    Clock businessClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
