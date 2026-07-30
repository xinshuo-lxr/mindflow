package com.xinshuo.mindflow.rag.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient syncHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    public OkHttpClient streamingHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    public Executor modelStreamExecutor() {
        return Executors.newCachedThreadPool();
    }
}
