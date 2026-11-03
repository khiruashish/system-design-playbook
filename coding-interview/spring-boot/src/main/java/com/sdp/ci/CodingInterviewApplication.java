package com.sdp.ci;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/** One Spring Boot 4 app that hosts the production version of each coding-interview problem, one package per problem. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
@EnableScheduling
public class CodingInterviewApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodingInterviewApplication.class, args);
    }
}
