package com.ownsudo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class OwnSudoApplication {
    public static void main(String[] args) {
        SpringApplication.run(OwnSudoApplication.class, args);
    }
}
