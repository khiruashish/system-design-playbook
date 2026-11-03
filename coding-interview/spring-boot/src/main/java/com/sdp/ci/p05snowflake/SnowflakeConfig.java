package com.sdp.ci.p05snowflake;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
public class SnowflakeConfig {
    private static final Logger log = LoggerFactory.getLogger(SnowflakeConfig.class);

    @Bean(destroyMethod = "release")                     // give the id back on graceful shutdown
    WorkerIdLease workerIdLease(StringRedisTemplate redis, SnowflakeProperties props) {
        WorkerIdLease lease = new WorkerIdLease(redis, props.workerLease());
        log.info("Snowflake worker id {} leased", lease.workerId());
        return lease;
    }

    @Bean
    SnowflakeIdGenerator snowflakeIdGenerator(WorkerIdLease lease, SnowflakeProperties props, Clock clock) {
        return new SnowflakeIdGenerator(props.epoch().toEpochMilli(), lease.workerId(), clock);
    }

    /** Renew every 10 s (lease is 30 s). Losing the lease means another instance may take our id: stop serving. */
    @Bean
    LeaseRenewer leaseRenewer(WorkerIdLease lease) { return new LeaseRenewer(lease); }

    public static class LeaseRenewer {
        private final WorkerIdLease lease;
        LeaseRenewer(WorkerIdLease lease) { this.lease = lease; }

        @Scheduled(fixedDelayString = "${ci.snowflake.renew-every:10s}")
        public void renew() {
            if (!lease.renew()) {
                log.error("Lost Snowflake worker lease {}: duplicate ids possible, failing health", lease.workerId());
                throw new IllegalStateException("worker id lease lost");
            }
        }
    }
}
