package com.xinshuo.mindflow;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MindFlow 核心应用启动类
 */
@SpringBootApplication
@EnableScheduling
@MapperScan(basePackages = {
        "com.xinshuo.mindflow.rag.dao.mapper",
        "com.xinshuo.mindflow.user.dao.mapper"
})
public class MindflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindflowApplication.class, args);
    }
}
