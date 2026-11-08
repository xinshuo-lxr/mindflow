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

package com.xinshuo.mindflow.rag.ratelimit;

import com.xinshuo.mindflow.MindflowApplication;
import com.xinshuo.mindflow.rag.service.ratelimit.FairDistributedRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分布式公平限流器核心行为测试（第 19 步）
 *
 * <p>每个用例使用独立的 limiter name（Redis key 前缀），互不干扰。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
class FairDistributedRateLimiterTest {

    @Autowired
    private RedissonClient redissonClient;

    private FairDistributedRateLimiter limiter;
    private ExecutorService callbackPool;

    @BeforeEach
    void setUp() {
        callbackPool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        if (limiter != null) {
            limiter.stop();
        }
        callbackPool.shutdownNow();
    }

    private FairDistributedRateLimiter newLimiter(int maxConcurrent, int pollIntervalMs) {
        String name = "test:limiter:" + Long.toHexString(System.nanoTime());
        FairDistributedRateLimiter l = new FairDistributedRateLimiter(
                name,
                redissonClient,
                () -> maxConcurrent,
                () -> 30,
                () -> pollIntervalMs);
        l.start();
        return l;
    }

    @Test
    void shouldAcquireImmediatelyWhenPermitAvailable() throws Exception {
        limiter = newLimiter(2, 100);
        CountDownLatch acquired = new CountDownLatch(1);

        limiter.acquire(FairDistributedRateLimiter.AcquireRequest.builder()
                .maxWaitMillis(5_000)
                .onAcquired(acquired::countDown)
                .onTimeout(() -> fail("不应超时"))
                .onAcquiredExecutor(callbackPool)
                .build());

        assertTrue(acquired.await(5, TimeUnit.SECONDS), "有空闲 permit 时应立即执行");
    }

    @Test
    void shouldQueueThenGrantAfterPermitReleased() throws Exception {
        limiter = newLimiter(1, 100);

        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch holdReleased = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);

        // A 抢到并占住唯一 permit
        limiter.acquire(FairDistributedRateLimiter.AcquireRequest.builder()
                .maxWaitMillis(5_000)
                .onAcquired(() -> {
                    firstAcquired.countDown();
                    try {
                        holdReleased.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .onTimeout(() -> fail("A 不应超时"))
                .onAcquiredExecutor(callbackPool)
                .build());
        assertTrue(firstAcquired.await(5, TimeUnit.SECONDS), "A 应拿到 permit");

        // B 排队等待
        limiter.acquire(FairDistributedRateLimiter.AcquireRequest.builder()
                .maxWaitMillis(15_000)
                .onAcquired(secondAcquired::countDown)
                .onTimeout(() -> fail("B 不应超时"))
                .onAcquiredExecutor(callbackPool)
                .build());

        assertFalse(secondAcquired.await(1, TimeUnit.SECONDS), "permit 被占用时 B 应排队等待");

        // 释放 A → B 应被放行
        holdReleased.countDown();
        assertTrue(secondAcquired.await(15, TimeUnit.SECONDS), "permit 释放后 B 应被放行");
        System.out.println("[2] 排队 → 放行 OK");
    }

    @Test
    void shouldTimeoutWhenWaitingTooLong() throws Exception {
        limiter = newLimiter(1, 100);

        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch holdReleased = new CountDownLatch(1);
        CountDownLatch timedOut = new CountDownLatch(1);
        AtomicBoolean wronglyAcquired = new AtomicBoolean(false);

        limiter.acquire(FairDistributedRateLimiter.AcquireRequest.builder()
                .maxWaitMillis(5_000)
                .onAcquired(() -> {
                    firstAcquired.countDown();
                    try {
                        holdReleased.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .onTimeout(() -> fail("A 不应超时"))
                .onAcquiredExecutor(callbackPool)
                .build());
        assertTrue(firstAcquired.await(5, TimeUnit.SECONDS));

        // B 只等 1 秒 → 应超时拒绝
        limiter.acquire(FairDistributedRateLimiter.AcquireRequest.builder()
                .maxWaitMillis(1_000)
                .onAcquired(() -> wronglyAcquired.set(true))
                .onTimeout(timedOut::countDown)
                .onAcquiredExecutor(callbackPool)
                .build());

        assertTrue(timedOut.await(10, TimeUnit.SECONDS), "等位超时应触发拒绝");
        assertFalse(wronglyAcquired.get(), "超时后不应再执行 onAcquired");
        holdReleased.countDown();
        System.out.println("[3] 等位超时拒绝 OK");
    }

    @Test
    void shouldNotGrantAfterCancel() throws Exception {
        limiter = newLimiter(1, 100);

        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch holdReleased = new CountDownLatch(1);
        AtomicReference<Runnable> cancelRef = new AtomicReference<>();
        AtomicBoolean secondAcquired = new AtomicBoolean(false);
        AtomicBoolean secondTimedOut = new AtomicBoolean(false);

        limiter.acquire(FairDistributedRateLimiter.AcquireRequest.builder()
                .maxWaitMillis(5_000)
                .onAcquired(() -> {
                    firstAcquired.countDown();
                    try {
                        holdReleased.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .onTimeout(() -> fail("A 不应超时"))
                .onAcquiredExecutor(callbackPool)
                .build());
        assertTrue(firstAcquired.await(5, TimeUnit.SECONDS));

        // B 注册取消钩子后主动取消（模拟 emitter 关闭）
        limiter.acquire(FairDistributedRateLimiter.AcquireRequest.builder()
                .maxWaitMillis(15_000)
                .onAcquired(() -> secondAcquired.set(true))
                .onTimeout(() -> secondTimedOut.set(true))
                .onAcquiredExecutor(callbackPool)
                .cancelBinder(cancelRef::set)
                .build());

        assertNotNull(cancelRef.get(), "应注册 cancel 回调");
        cancelRef.get().run();

        // 释放 A 后 B 也不应被放行
        holdReleased.countDown();
        Thread.sleep(2_000);
        assertFalse(secondAcquired.get(), "取消后不应执行 onAcquired");
        assertFalse(secondTimedOut.get(), "取消后不应执行 onTimeout");
        System.out.println("[4] 取消后不回调 OK");
    }
}
