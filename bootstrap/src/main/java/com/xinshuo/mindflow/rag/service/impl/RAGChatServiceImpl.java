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

package com.xinshuo.mindflow.rag.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;

import com.xinshuo.mindflow.framework.context.UserContext;
import com.xinshuo.mindflow.framework.convention.ChatMessage;
import com.xinshuo.mindflow.framework.convention.ChatRequest;
import com.xinshuo.mindflow.infra.chat.LLMService;
import com.xinshuo.mindflow.infra.chat.StreamCallback;
import com.xinshuo.mindflow.infra.chat.StreamCancellationHandle;
import com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeBaseDO;
import com.xinshuo.mindflow.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.xinshuo.mindflow.rag.core.memory.ConversationMemoryService;
import com.xinshuo.mindflow.rag.core.prompt.RAGPromptService;
import com.xinshuo.mindflow.rag.service.RAGChatService;
import com.xinshuo.mindflow.rag.service.handler.StreamCallbackFactory;
import com.xinshuo.mindflow.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 对话服务实现——LLM 流式对话，支持多轮对话记忆 + RAG 检索
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final LLMService llmService;
    private final StreamCallbackFactory callbackFactory;
    private final StreamTaskManager taskManager;
    private final ConversationMemoryService memoryService;
    private final RAGPromptService ragPromptService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public void streamChat(String message, String conversationId, String kbId, SseEmitter emitter) {
        String userId = UserContext.getUserId();
        String actualConversationId = StrUtil.isBlank(conversationId)
                ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = IdUtil.getSnowflakeNextIdStr();

        StreamCallback callback = callbackFactory.createChatEventHandler(
                emitter, actualConversationId, taskId);

        List<ChatMessage> history = memoryService.loadAndAppend(
                actualConversationId, userId, ChatMessage.user(message));

        List<ChatMessage> messages = new ArrayList<>(history);

        // Step 10: RAG 检索——有 kbId 时，检索知识库拼入 system prompt
        if (StrUtil.isNotBlank(kbId)) {
            KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
            if (kb != null) {
                String context = ragPromptService.buildContext(
                        message, kb.getCollectionName(), 5);
                if (StrUtil.isNotBlank(context)) {
                    String systemPrompt = "你是知识库「" + kb.getName() + "」的智能助手。"
                            + "请严格根据以下参考资料回答用户问题。"
                            + "如果参考资料不足以回答，请如实告知'该知识库中暂无相关信息'。\n\n"
                            + context;
                    messages.add(0, ChatMessage.system(systemPrompt));
                }
            }
        }

        messages.add(ChatMessage.user(message));

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .build();

        StreamCancellationHandle handle = llmService.streamChat(chatRequest, callback);
        taskManager.bindHandle(taskId, handle);
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }
}
