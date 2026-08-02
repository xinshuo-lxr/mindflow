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

package com.xinshuo.mindflow.rag.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import com.xinshuo.mindflow.framework.convention.Result;
import com.xinshuo.mindflow.framework.web.Results;
import com.xinshuo.mindflow.rag.config.RAGDefaultProperties;
import com.xinshuo.mindflow.rag.dto.ChatStreamRequest;
import com.xinshuo.mindflow.rag.service.RAGChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;

/**
 * RAG 对话控制器——提供 SSE 流式对话与任务取消接口
 */
@Slf4j
@RestController
public class RAGChatController {

    private final RAGChatService ragChatService;
    private final RAGDefaultProperties ragDefaultProperties;
    private final Executor chatExecutor;

    public RAGChatController(RAGChatService ragChatService,
                             RAGDefaultProperties ragDefaultProperties,
                             @Qualifier("chatExecutor") Executor chatExecutor) {
        this.ragChatService = ragChatService;
        this.ragDefaultProperties = ragDefaultProperties;
        this.chatExecutor = chatExecutor;
    }

    /**
     * 发起 SSE 流式对话
     */
    @PostMapping(value = "/chat/send-stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatStreamRequest request) {
        SseEmitter emitter = new SseEmitter(ragDefaultProperties.getSseTimeoutMs());
        String conversationId = StrUtil.blankToDefault(request.getConversationId(), null);
        chatExecutor.execute(() -> {
            try {
                ragChatService.streamChat(request.getMessage(), conversationId, emitter);
            } catch (Exception e) {
                log.error("流式对话异常", e);
                // SSE 响应不能再由全局异常处理器写入 JSON Result。
                emitter.complete();
            }
        });
        return emitter;
    }

    /**
     * 停止指定任务
     */
    @PostMapping(value = "/chat/stop")
    public Result<Void> stop(@RequestParam("taskId") String taskId) {
        ragChatService.stopTask(taskId);
        return Results.success();
    }
}



