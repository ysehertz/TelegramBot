package com.bot.aabot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Date;

@EnableScheduling
@SpringBootApplication
public class AaBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AaBotApplication.class, args);
    }
}
