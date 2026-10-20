package com.plinko;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PlinkoApplication {

    public static void main(String[] args) {
        // Timestamps are stored as UTC; audit hashes and round signatures depend on exact round-trips.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(PlinkoApplication.class, args);
    }
}
