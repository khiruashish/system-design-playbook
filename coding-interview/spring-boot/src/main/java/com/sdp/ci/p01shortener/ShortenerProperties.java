package com.sdp.ci.p01shortener;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ci.shortener")
public record ShortenerProperties(String baseUrl, int idBlockSize) {}
