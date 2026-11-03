package com.sdp.ci.p05snowflake;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ci.snowflake")
public record SnowflakeProperties(Instant epoch, Duration workerLease) {}
