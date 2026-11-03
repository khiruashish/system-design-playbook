package com.sdp.ci.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Inject a Clock instead of calling System.currentTimeMillis(), so time-based logic is testable. */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
