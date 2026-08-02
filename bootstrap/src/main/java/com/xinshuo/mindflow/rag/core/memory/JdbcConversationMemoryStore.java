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
import com.xinshuo.mindflow.rag.config.MemoryProperties;
import com.xinshuo.mindflow.rag.controller.vo.ConversationMessageVO;
import com.xinshuo.mindflow.rag.enums.ConversationMessageOrder;
import com.xinshuo.mindflow.rag.service.ConversationMessageService;
import com.xinshuo.mindflow.rag.service.ConversationService;
import com.xinshuo.mindflow.rag.service.bo.ConversationCreateBO;
import com.xinshuo.mindflow.rag.service.bo.ConversationMessageBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 JDBC 的对话记忆存储实现
 * <p>
 * 委托 {@link ConversationMessageService} 和 {@link ConversationService} 完成
 * 消息的读写与会话的创建/更新。每次加载历史时按 {@link MemoryProperties#getHistoryKeepTurns()}
 * 控制加载条数（轮数 × 2 = 消息数），加载后过滤掉开头的 ASSISTANT 消息保证
 * 历史始终以 USER 开头（多轮 LLM 调用需要 user/assistant 交替）。
 */
@Slf4j
@Service
public class JdbcConversationMemoryStore implements ConversationMemoryStore {

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;
    private final MemoryProperties memoryProperties;

    public JdbcConversationMemoryStore(ConversationService conversationService,
                                       ConversationMessageService conversationMessageService,
                                       MemoryProperties memoryProperties) {
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.memoryProperties = memoryProperties;
    }

    @Override
    public List<ChatMessage> loadHistory(String conversationId, String userId) {
        int maxMessages = resolveMaxHistoryMessages();
        // DESC 取最新 N 条，后续反序得到时间升序
        List<ConversationMessageVO> dbMessages = conversationMessageService.listMessages(
                conversationId,
                userId,
                maxMessages,
                ConversationMessageOrder.DESC
        );
        if (CollUtil.isEmpty(dbMessages)) {
            return List.of();
        }

        List<ChatMessage> result = dbMessages.stream()
                .map(this::toChatMessage)
                .filter(this::isHistoryMessage)
                .collect(Collectors.toList());

        // 去掉开头的 ASSISTANT 消息（上一轮未完成的异常情况），确保历史以 USER 开头
        return normalizeHistory(result);
    }

    @Override
    public String append(String conversationId, String userId, ChatMessage message) {
        // 1. 持久化消息
        ConversationMessageBO conversationMessage = ConversationMessageBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(message.getRole().name().toLowerCase())
                .content(message.getContent())
                .thinkingContent(message.getThinkingContent())
                .thinkingDuration(message.getThinkingDuration())
                .build();
        String messageId = conversationMessageService.addMessage(conversationMessage);

        // 2. USER 消息时自动创建/更新会话——保证首条消息触发会话创建 + 标题生成
        if (message.getRole() == ChatMessage.Role.USER) {
            ConversationCreateBO conversation = ConversationCreateBO.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .question(message.getContent())
                    .lastTime(new Date())
                    .build();
            conversationService.createOrUpdate(conversation);
        }
        return messageId;
    }

    @Override
    public void refreshCache(String conversationId, String userId) {
        // JDBC 直读模式，无需刷新缓存
    }

    private ChatMessage toChatMessage(ConversationMessageVO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        return new ChatMessage(
                ChatMessage.Role.fromString(record.getRole()),
                record.getContent()
        );
    }

    /**
     * 去除历史记录开头连续的 ASSISTANT 消息。
     * <p>
     * 场景：上一轮 AI 回复完成后附加的系统消息 or 异常中断遗留的孤立 assistant
     * 消息。LLM 的多轮对话需要 user/assistant 交替，以 ASSISTANT 开头会导致
     * API 报错或模型困惑。
     */
    private List<ChatMessage> normalizeHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int start = 0;
        while (start < messages.size() && messages.get(start).getRole() == ChatMessage.Role.ASSISTANT) {
            start++;
        }
        if (start >= messages.size()) {
            return List.of();
        }
        return messages.subList(start, messages.size());
    }

    /**
     * 过滤非法消息——必须为 USER 或 ASSISTANT 角色且内容非空。
     * SYSTEM 消息由业务层显式构建，不存入记忆。
     */
    private boolean isHistoryMessage(ChatMessage message) {
        return message != null
                && (message.getRole() == ChatMessage.Role.USER || message.getRole() == ChatMessage.Role.ASSISTANT)
                && StrUtil.isNotBlank(message.getContent());
    }

    private int resolveMaxHistoryMessages() {
        int maxTurns = memoryProperties.getHistoryKeepTurns();
        return maxTurns * 2;
    }
}
