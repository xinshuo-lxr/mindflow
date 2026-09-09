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
import com.xinshuo.mindflow.rag.core.intent.IntentNode;
import com.xinshuo.mindflow.rag.core.intent.NodeScore;
import com.xinshuo.mindflow.rag.core.memory.ConversationMemoryService;
import com.xinshuo.mindflow.rag.core.rewrite.QueryRewriteService;
import com.xinshuo.mindflow.rag.core.rewrite.RewriteResult;
import com.xinshuo.mindflow.rag.core.retrieve.RetrievalEngine;
import com.xinshuo.mindflow.rag.dto.RetrievalContext;
import com.xinshuo.mindflow.rag.dto.SubQuestionIntent;
import com.xinshuo.mindflow.rag.enums.IntentKind;
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
 * RAG 对话服务实现——LLM 流式对话，支持多轮对话记忆 + 多渠道检索
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final LLMService llmService;
    private final StreamCallbackFactory callbackFactory;
    private final StreamTaskManager taskManager;
    private final ConversationMemoryService memoryService;
    private final RetrievalEngine retrievalEngine;
    private final QueryRewriteService queryRewriteService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public void streamChat(String message, String conversationId, Boolean deepThinking, String kbId, SseEmitter emitter) {
        String userId = UserContext.getUserId();
        String actualConversationId = StrUtil.isBlank(conversationId)
                ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = IdUtil.getSnowflakeNextIdStr();

        StreamCallback callback = callbackFactory.createChatEventHandler(
                emitter, actualConversationId, taskId);

        List<ChatMessage> history = memoryService.loadAndAppend(
                actualConversationId, userId, ChatMessage.user(message));

        List<ChatMessage> messages = new ArrayList<>(history);

        // Step 17: 多渠道检索——有 kbId 时，改写问题 → 构造指向该 KB 的高置信意图 → 多渠道检索引擎（意图定向精查 + 全局兜底）→ 拼 system prompt
        if (StrUtil.isNotBlank(kbId)) {
            KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
            if (kb != null) {
                // Step 12: 查询改写 + 多问句拆分——用改写后的问题检索，提升召回命中率
                RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(message, history);
                String retrievalQuery = rewriteResult.rewrittenQuestion();

                // 构造指向目标知识库 collection 的临时 KB 意图（置信度 1.0 → 意图定向通道生效、全局通道不兜底）
                IntentNode intentNode = IntentNode.builder()
                        .id("kb-" + kbId)
                        .name(kb.getName())
                        .collectionName(kb.getCollectionName())
                        .kind(IntentKind.KB)
                        .topK(5)
                        .build();
                SubQuestionIntent subIntent = new SubQuestionIntent(
                        retrievalQuery, List.of(new NodeScore(intentNode, 1.0)));
                RetrievalContext retrievalContext = retrievalEngine.retrieve(List.of(subIntent), 5);

                if (retrievalContext.hasKb()) {
                    String systemPrompt = "你是知识库「" + kb.getName() + "」的智能助手。"
                            + "请严格根据以下参考资料回答用户问题。"
                            + "如果参考资料不足以回答，请如实告知'该知识库中暂无相关信息'。\n\n"
                            + retrievalContext.getKbContext();
                    messages.add(0, ChatMessage.system(systemPrompt));
                }
            }
        }

        messages.add(ChatMessage.user(message));

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(Boolean.TRUE.equals(deepThinking))
                .build();

        StreamCancellationHandle handle = llmService.streamChat(chatRequest, callback);
        taskManager.bindHandle(taskId, handle);
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }
}
