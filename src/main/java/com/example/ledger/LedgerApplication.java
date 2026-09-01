package com.example.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableKafka
@EnableScheduling
@SpringBootApplication
public class LedgerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
