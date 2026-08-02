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

package com.xinshuo.mindflow.rag.service.handler;

import com.xinshuo.mindflow.infra.chat.StreamCallback;
import com.xinshuo.mindflow.infra.config.AIModelProperties;
import com.xinshuo.mindflow.rag.core.memory.ConversationMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * StreamCallback 工厂——创建带持久化能力的 StreamChatEventHandler
 */
@Component
@RequiredArgsConstructor
public class StreamCallbackFactory {

    private final AIModelProperties modelProperties;
    private final StreamTaskManager taskManager;
    private final ConversationMemoryService memoryService;

    public StreamCallback createChatEventHandler(SseEmitter emitter, String conversationId, String taskId) {
        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId(conversationId)
                .taskId(taskId)
                .modelProperties(modelProperties)
                .taskManager(taskManager)
                .memoryService(memoryService)
                .build();
        return new StreamChatEventHandler(params);
    }
}
