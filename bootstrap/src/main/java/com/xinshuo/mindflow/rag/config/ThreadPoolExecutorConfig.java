/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xinshuo.mindflow.rag.config;

import com.alibaba.ttl.threadpool.TtlExecutors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池配置
 */
@Configuration
public class ThreadPoolExecutorConfig {

    private final AtomicInteger threadId = new AtomicInteger(0);

    /**
     * SSE 流式对话线程池——不阻塞 Tomcat 工作线程
     */
    @Bean
    public Executor chatExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 16,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "chat-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 记忆加载线程池——并行加载摘要 + 历史消息
     */
    @Bean
    public Executor memoryLoadExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "memory-load-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 意图分类线程池——并行对各子问题进行意图识别
     */
    @Bean
    public Executor intentClassifyExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "intent-classify-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 记忆摘要线程池——异步生成会话摘要，不阻塞对话主流程
     */
    @Bean
    public Executor memorySummaryExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "memory-summary-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 多通道并行检索线程池——并行执行各检索通道
     */
    @Bean
    public Executor ragRetrievalExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 8,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "rag-retrieval-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 通道内单目标并行检索线程池——并行检索多个意图/collection
     */
    @Bean
    public Executor innerRetrievalExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 8,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "inner-retrieval-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * RAG 上下文构建线程池——并行构建各子问题的 KB/MCP 上下文
     */
    @Bean
    public Executor ragContextExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "rag-context-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * MCP 工具批量调用线程池——并行执行多个 MCP 工具
     */
    @Bean
    public Executor mcpBatchExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "mcp-batch-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 知识库文档分块线程池——定时刷新任务在此异步执行
     * <p>
     * 队列满时抛 RejectedExecutionException，由定时扫描逻辑捕获并释放调度锁
     */
    @Bean
    public Executor knowledgeChunkExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(2, cores / 2), Math.max(4, cores),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "kb-chunk-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * SSE 排队后执行入口线程池——与全局限流 permit 数一致
     * <p>
     * core = max = max-concurrent，SynchronousQueue 无缓冲，AbortPolicy 在饱和时抛
     * RejectedExecutionException 交由 {@code ChatQueueLimiter} 走 reject 流程
     */
    @Bean
    public Executor chatEntryExecutor(RAGRateLimitProperties rateLimitProperties) {
        int size = rateLimitProperties.getGlobalMaxConcurrent();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                size, size,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "chat-entry-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return TtlExecutors.getTtlExecutor(executor);
    }
}
