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

package com.xinshuo.mindflow.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.xinshuo.mindflow.framework.convention.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 对话记忆服务默认实现
 * <p>
 * 负责加载和追加对话记忆。加载时并行拉取摘要和历史记录，通过
 * {@link CompletableFuture#allOf} 等待两者完成后合并——总耗时
 * = max(摘要耗时, 历史耗时) 而非 sum，减少 RAG 管道的首包延迟。
 * <p>
 * 容错策略：摘要加载失败返回 null（不阻塞对话），历史加载失败返回空列表
 * （后续 LLM 调用无上下文，但不会报错中断流）。
 * <p>
 * 每轮 append 后触发 {@link ConversationMemorySummaryService#compressIfNeeded}，
 * 由 summaryService 内部根据轮数阈值决定是否真正执行压缩。
 */
@Slf4j
@Service
public class DefaultConversationMemoryService implements ConversationMemoryService {

    private final ConversationMemoryStore memoryStore;
    private final ConversationMemorySummaryService summaryService;
    private final Executor memoryLoadExecutor;

    public DefaultConversationMemoryService(ConversationMemoryStore memoryStore,
                                            ConversationMemorySummaryService summaryService,
                                            @Qualifier("memoryLoadExecutor") Executor memoryLoadExecutor) {
        this.memoryStore = memoryStore;
        this.summaryService = summaryService;
        this.memoryLoadExecutor = memoryLoadExecutor;
    }

    @Override
    public List<ChatMessage> load(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }

        long startTime = System.currentTimeMillis();
        try {
            // 并行加载摘要和历史记录，两者互不依赖
            CompletableFuture<ChatMessage> summaryFuture = CompletableFuture.supplyAsync(
                    () -> loadSummaryWithFallback(conversationId, userId), memoryLoadExecutor
            );
            CompletableFuture<List<ChatMessage>> historyFuture = CompletableFuture.supplyAsync(
                    () -> loadHistoryWithFallback(conversationId, userId), memoryLoadExecutor
            );

            // 等待所有任务完成后合并结果
            return CompletableFuture.allOf(summaryFuture, historyFuture)
                    .thenApply(v -> {
                        ChatMessage summary = summaryFuture.join();
                        List<ChatMessage> history = historyFuture.join();
                        log.debug("加载对话记忆 - conversationId: {}, userId: {}, 摘要: {}, 历史消息数: {}, 耗时: {}ms",
                                conversationId, userId, summary != null, history.size(),
                                System.currentTimeMillis() - startTime);
                        return attachSummary(summary, history);
                    })
                    .join();
        } catch (Exception e) {
            log.error("加载对话记忆失败 - conversationId: {}, userId: {}", conversationId, userId, e);
            return List.of();
        }
    }

    /**
     * 加载摘要，失败时返回 null 不阻塞主流程
     */
    private ChatMessage loadSummaryWithFallback(String conversationId, String userId) {
        try {
            return summaryService.loadLatestSummary(conversationId, userId);
        } catch (Exception e) {
            log.warn("加载摘要失败，将跳过摘要 - conversationId: {}, userId: {}", conversationId, userId, e);
            return null;
        }
    }

    /**
     * 加载历史记录，失败时返回空列表
     */
    private List<ChatMessage> loadHistoryWithFallback(String conversationId, String userId) {
        try {
            List<ChatMessage> history = memoryStore.loadHistory(conversationId, userId);
            return history != null ? history : List.of();
        } catch (Exception e) {
            log.error("加载历史记录失败 - conversationId: {}, userId: {}", conversationId, userId, e);
            return List.of();
        }
    }

    @Override
    public String append(String conversationId, String userId, ChatMessage message) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        String messageId = memoryStore.append(conversationId, userId, message);
        // 每轮对话后检查是否需要触发摘要压缩
        summaryService.compressIfNeeded(conversationId, userId, message);
        return messageId;
    }

    /**
     * 将摘要拼接在历史消息之前——摘要放在最前面让 LLM 先读"历史概要"，
     * 再读具体消息，符合 attention 机制中靠前的 token 权重更高的特性。
     */
    private List<ChatMessage> attachSummary(ChatMessage summary, List<ChatMessage> messages) {
        if (CollUtil.isEmpty(messages)) {
            return List.of();
        }
        if (summary == null) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>();
        result.add(summaryService.decorateIfNeeded(summary));
        result.addAll(messages);
        return result;
    }
}

