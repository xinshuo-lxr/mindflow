package com.xinshuo.mindflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MindflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindflowApplication.class, args);
    }
}
