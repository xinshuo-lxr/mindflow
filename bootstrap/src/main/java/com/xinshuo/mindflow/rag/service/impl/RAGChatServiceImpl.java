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

import com.xinshuo.mindflow.framework.convention.ChatMessage;
import com.xinshuo.mindflow.framework.convention.ChatRequest;
import com.xinshuo.mindflow.infra.chat.LLMService;
import com.xinshuo.mindflow.infra.chat.StreamCallback;
import com.xinshuo.mindflow.infra.chat.StreamCancellationHandle;
import com.xinshuo.mindflow.rag.service.RAGChatService;
import com.xinshuo.mindflow.rag.service.handler.StreamCallbackFactory;
import com.xinshuo.mindflow.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * RAG 对话服务默认实现——第 5 步：纯 LLM 流式对话。
 *
 * <p>后续步骤会在此之上叠加：会话记忆 → 向量检索 → 意图分类 → ... → 8 阶段管道
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final LLMService llmService;
    private final StreamCallbackFactory callbackFactory;
    private final StreamTaskManager taskManager;

    @Override
    public void streamChat(String message, SseEmitter emitter) {
        String taskId = IdUtil.getSnowflakeNextIdStr();
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, taskId);

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(message)))
                .build();

        StreamCancellationHandle handle = llmService.streamChat(chatRequest, callback);
        taskManager.bindHandle(taskId, handle);
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }
}
