package com.company.triage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TriageMate — local Spring Boot POC (hackathon).
 *
 * <p>{@code @EnableScheduling} exists for the K1 outbound poller
 * ({@link com.company.triage.orchestration.IncidentPoller}). The poller bean itself is
 * gated on {@code triage.trigger.poll.enabled=true} and off by default, so enabling
 * scheduling here starts no background work on its own.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TriageMateApplication {
    public static void main(String[] args) {
        SpringApplication.run(TriageMateApplication.class, args);
    }
}
