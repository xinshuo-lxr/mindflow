package com.xinshuo.mindflow.infra.chat;

import com.xinshuo.mindflow.framework.trace.RagStreamTraceSupport;
import okhttp3.OkHttpClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@TestConfiguration
public class ChatTestConfig {

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
                .readTimeout(Duration.ofMinutes(5))   // 流式可能很长
                .writeTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    public Executor modelStreamExecutor() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    public RagStreamTraceSupport streamTraceSupport() {
        return (name, type) -> RagStreamTraceSupport.NOOP_SPAN;
    }
}
