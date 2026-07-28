package com.company.triage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** TriageMate — local Spring Boot POC (hackathon). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TriageMateApplication {
    public static void main(String[] args) {
        SpringApplication.run(TriageMateApplication.class, args);
    }
}
