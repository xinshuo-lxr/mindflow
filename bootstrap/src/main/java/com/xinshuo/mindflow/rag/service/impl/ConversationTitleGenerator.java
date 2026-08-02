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

import com.xinshuo.mindflow.framework.convention.ChatMessage;
import com.xinshuo.mindflow.framework.convention.ChatRequest;
import com.xinshuo.mindflow.infra.chat.LLMService;
import com.xinshuo.mindflow.rag.config.MemoryProperties;
import com.xinshuo.mindflow.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.xinshuo.mindflow.rag.constant.RAGConstant.CONVERSATION_TITLE_PROMPT_PATH;

/**
 * 会话标题生成器
 * <p>
 * 拆为独立 bean 是为了让 Spring AOP 的 {@code @RagTraceNode} 拦截生效——
 * 同类 self-call 不会触发 proxy，若放在 ConversationServiceImpl 内部直接调
 * private 方法，标题生成的 LLM 调用无法挂在 trace 节点下，会变成孤立的 root 节点。
 * <p>
 * LLM 调用失败时返回默认标题"新对话"，不抛异常阻塞会话创建流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationTitleGenerator {

    private final MemoryProperties memoryProperties;
    private final PromptTemplateLoader promptTemplateLoader;
    private final LLMService llmService;

    /**
     * 根据用户首条消息生成会话标题
     *
     * @param question 用户首条消息内容
     * @return 生成的标题，失败返回 "新对话"
     */
    public String generate(String question) {
        int maxLen = memoryProperties.getTitleMaxLength();
        if (maxLen <= 0) {
            maxLen = 30;
        }
        String prompt = promptTemplateLoader.render(
                CONVERSATION_TITLE_PROMPT_PATH,
                Map.of(
                        "title_max_chars", String.valueOf(maxLen),
                        "question", question
                )
        );

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(prompt)))
                    .temperature(0.7D)
                    .topP(0.3D)
                    .thinking(false)
                    .build();
            return llmService.chat(request);
        } catch (Exception ex) {
            log.warn("生成会话标题失败", ex);
            return "新对话";
        }
    }
}
