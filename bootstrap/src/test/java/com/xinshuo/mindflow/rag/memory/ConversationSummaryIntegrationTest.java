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

package com.xinshuo.mindflow.rag.memory;

import com.xinshuo.mindflow.MindflowApplication;
import com.xinshuo.mindflow.framework.convention.ChatMessage;
import com.xinshuo.mindflow.rag.core.memory.ConversationMemoryService;
import com.xinshuo.mindflow.rag.core.memory.ConversationMemorySummaryService;
import com.xinshuo.mindflow.rag.dao.entity.ConversationSummaryDO;
import com.xinshuo.mindflow.rag.service.ConversationGroupService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话记忆摘要集成测试（第 15 步）
 *
 * <p>开启摘要配置（summary-enabled=true, start-turns=5, keep-turns=4），
 * 通过 ConversationMemoryService.append 模拟 5 轮对话触发异步摘要生成，
 * 验证 t_conversation_summary 落库 + loadLatestSummary + decorateIfNeeded。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class,
        properties = {
                "rag.memory.summary-enabled=true",
                "rag.memory.summary-start-turns=5",
                "rag.memory.history-keep-turns=4"
        }
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConversationSummaryIntegrationTest {

    @Autowired
    private ConversationMemoryService memoryService;

    @Autowired
    private ConversationMemorySummaryService summaryService;

    @Autowired
    private ConversationGroupService conversationGroupService;

    private static String conversationId;
    private static final String USER_ID = "summary-test-user";

    private String suffix() {
        // t_message.conversation_id 为 VARCHAR(20)，需保持短 ID
        return String.valueOf(System.currentTimeMillis()).substring(7);
    }

    @Test
    @Order(1)
    void shouldTriggerSummaryAfterTurns() throws Exception {
        conversationId = "s" + suffix();

        // 模拟 5 轮对话（5 user + 5 assistant），user 消息数达到 start-turns=5
        for (int i = 1; i <= 5; i++) {
            memoryService.append(conversationId, USER_ID, ChatMessage.user("第" + i + "轮：年假怎么算？"));
            memoryService.append(conversationId, USER_ID, ChatMessage.assistant("根据规定，入职满一年享5天年假。"));
        }

        // 异步摘要生成，等待执行完成
        Thread.sleep(10000);

        // 验证摘要已落库
        ConversationSummaryDO summary = conversationGroupService.findLatestSummary(conversationId, USER_ID);
        assertNotNull(summary, "多轮对话后应生成会话摘要");
        assertNotNull(summary.getContent(), "摘要内容不应为 null");
        assertFalse(summary.getContent().isBlank(), "摘要内容不应为空");
        assertNotNull(summary.getLastMessageId(), "摘要应记录覆盖的最后消息 ID");
        System.out.println("[1] 摘要已生成: content=" + summary.getContent());
    }

    @Test
    @Order(2)
    void shouldLoadLatestSummary() {
        assertNotNull(conversationId, "需要先完成摘要生成");

        ChatMessage summary = summaryService.loadLatestSummary(conversationId, USER_ID);
        assertNotNull(summary, "loadLatestSummary 应返回摘要");
        assertEquals(ChatMessage.Role.SYSTEM, summary.getRole(), "摘要应为 SYSTEM 角色");
        assertFalse(summary.getContent().isBlank(), "摘要内容不应为空");
        System.out.println("[2] loadLatestSummary OK");
    }

    @Test
    @Order(3)
    void shouldDecorateSummaryWithWrapper() {
        assertNotNull(conversationId, "需要先完成摘要生成");

        ChatMessage summary = summaryService.loadLatestSummary(conversationId, USER_ID);
        ChatMessage decorated = summaryService.decorateIfNeeded(summary);

        assertNotNull(decorated, "装饰后的摘要不应为 null");
        assertEquals(ChatMessage.Role.SYSTEM, decorated.getRole(), "装饰后仍为 SYSTEM 角色");
        // summary-wrapper section 会包裹摘要内容
        assertTrue(decorated.getContent().contains(summary.getContent().trim()),
                "装饰后内容应包含原摘要");
        System.out.println("[3] decorateIfNeeded OK, 包装长度=" + decorated.getContent().length());
    }

    @Test
    @Order(4)
    void shouldReturnNullWhenNoSummary() {
        ChatMessage summary = summaryService.loadLatestSummary("non-existent-conv-" + suffix(), USER_ID);
        assertNull(summary, "不存在的会话应返回 null 摘要");
        System.out.println("[4] 无摘要返回 null OK");
    }
}
