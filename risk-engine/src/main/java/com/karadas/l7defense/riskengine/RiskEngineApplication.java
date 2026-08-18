package com.karadas.l7defense.riskengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
public class RiskEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskEngineApplication.class, args);
    }

    /**
     * Injected everywhere time is read, so tests can advance the clock by hand
     * instead of sleeping through a three-minute window.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}