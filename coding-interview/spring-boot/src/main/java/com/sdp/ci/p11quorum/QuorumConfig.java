package com.sdp.ci.p11quorum;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class QuorumConfig {
    @Bean
    QuorumCoordinator quorumCoordinator(RestClient.Builder builder, QuorumProperties props, Clock clock) {
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(props.timeout()).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.timeout());          // a slow replica must not hold the request
        return new QuorumCoordinator(builder.requestFactory(factory).build(), props, clock);
    }
}
