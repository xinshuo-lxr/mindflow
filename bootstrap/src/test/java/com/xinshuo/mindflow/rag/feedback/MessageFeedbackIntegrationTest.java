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

package com.xinshuo.mindflow.rag.feedback;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xinshuo.mindflow.MindflowApplication;
import com.xinshuo.mindflow.framework.context.LoginUser;
import com.xinshuo.mindflow.framework.context.UserContext;
import com.xinshuo.mindflow.framework.exception.ClientException;
import com.xinshuo.mindflow.rag.controller.request.MessageFeedbackRequest;
import com.xinshuo.mindflow.rag.dao.entity.ConversationMessageDO;
import com.xinshuo.mindflow.rag.dao.entity.MessageFeedbackDO;
import com.xinshuo.mindflow.rag.dao.mapper.ConversationMessageMapper;
import com.xinshuo.mindflow.rag.dao.mapper.MessageFeedbackMapper;
import com.xinshuo.mindflow.rag.service.MessageFeedbackService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息反馈集成测试（第 20 步）
 *
 * <p>直接调用同步路径 {@code submitFeedback}（异步路径依赖 RocketMQ，本地无 Broker），
 * 验证：点赞落库 → 改踩覆盖（upsert 不新增行）→ 批量回显 → 非法参数/非助手消息拦截。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MessageFeedbackIntegrationTest {

    @Autowired
    private MessageFeedbackService feedbackService;

    @Autowired
    private MessageFeedbackMapper feedbackMapper;

    @Autowired
    private ConversationMessageMapper messageMapper;

    private static final String SUFFIX = String.valueOf(System.currentTimeMillis()).substring(7);
    private static final String USER_ID = "fb" + SUFFIX;
    private static final String CONVERSATION_ID = "fbc" + SUFFIX;

    private static String assistantMessageId;
    private static String userMessageId;

    @BeforeAll
    static void setUpContext() {
        UserContext.set(LoginUser.builder().userId(USER_ID).username("feedback-tester").build());
    }

    @AfterAll
    static void tearDownContext() {
        UserContext.clear();
    }

    @Test
    @Order(1)
    void shouldPrepareMessages() {
        ConversationMessageDO assistant = ConversationMessageDO.builder()
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .role("assistant")
                .content("这是助手回答")
                .build();
        messageMapper.insert(assistant);
        assistantMessageId = assistant.getId();
        assertNotNull(assistantMessageId);

        ConversationMessageDO user = ConversationMessageDO.builder()
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .role("user")
                .content("这是用户提问")
                .build();
        messageMapper.insert(user);
        userMessageId = user.getId();

        System.out.println("[1] 准备消息: assistant=" + assistantMessageId + ", user=" + userMessageId);
    }

    @Test
    @Order(2)
    void shouldInsertLikeFeedback() {
        feedbackService.submitFeedback(assistantMessageId, request(1, "内容有用", "很好"));

        List<MessageFeedbackDO> rows = feedbackMapper.selectList(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getMessageId, assistantMessageId)
                        .eq(MessageFeedbackDO::getUserId, USER_ID)
                        .eq(MessageFeedbackDO::getDeleted, 0));

        assertEquals(1, rows.size(), "首次反馈应插入一行");
        assertEquals(1, rows.get(0).getVote());
        assertEquals(CONVERSATION_ID, rows.get(0).getConversationId(), "应回填会话ID");
        System.out.println("[2] 点赞落库 OK, id=" + rows.get(0).getId());
    }

    @Test
    @Order(3)
    void shouldUpsertInsteadOfDuplicate() throws Exception {
        List<MessageFeedbackDO> before = feedbackMapper.selectList(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getMessageId, assistantMessageId)
                        .eq(MessageFeedbackDO::getUserId, USER_ID)
                        .eq(MessageFeedbackDO::getDeleted, 0));
        String firstId = before.get(0).getId();

        // 时间守卫用 update_time < submitTime，等待跨毫秒避免边界
        Thread.sleep(30);
        feedbackService.submitFeedback(assistantMessageId, request(-1, "内容不准确", null));

        List<MessageFeedbackDO> after = feedbackMapper.selectList(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getMessageId, assistantMessageId)
                        .eq(MessageFeedbackDO::getUserId, USER_ID)
                        .eq(MessageFeedbackDO::getDeleted, 0));

        assertEquals(1, after.size(), "重复反馈应更新同一行，不新增");
        assertEquals(firstId, after.get(0).getId(), "应更新原行");
        assertEquals(-1, after.get(0).getVote(), "投票应被覆盖为点踩");
        System.out.println("[3] 改踩 upsert OK, vote=" + after.get(0).getVote());
    }

    @Test
    @Order(4)
    void shouldReturnVotesInBatch() {
        Map<String, Integer> votes = feedbackService.getUserVotes(USER_ID, List.of(assistantMessageId));
        assertEquals(-1, votes.get(assistantMessageId), "批量回显应返回当前投票");

        Map<String, Integer> empty = feedbackService.getUserVotes(USER_ID, List.of());
        assertTrue(empty.isEmpty(), "空消息列表应返回空 Map");
        System.out.println("[4] getUserVotes OK: " + votes);
    }

    @Test
    @Order(5)
    void shouldRejectInvalidVote() {
        ClientException ex = assertThrows(ClientException.class,
                () -> feedbackService.submitFeedback(assistantMessageId, request(0, null, null)));
        assertTrue(ex.getMessage().contains("1 或 -1"), "应提示投票值非法");

        ClientException ex2 = assertThrows(ClientException.class,
                () -> feedbackService.submitFeedback(assistantMessageId, request(null, null, null)));
        assertTrue(ex2.getMessage().contains("不能为空"));
        System.out.println("[5] 非法投票值拦截 OK");
    }

    @Test
    @Order(6)
    void shouldRejectNonAssistantMessage() {
        ClientException ex = assertThrows(ClientException.class,
                () -> feedbackService.submitFeedback(userMessageId, request(1, null, null)));
        assertTrue(ex.getMessage().contains("助手消息"), "仅允许对助手消息反馈");
        System.out.println("[6] 非助手消息拦截 OK");
    }

    private MessageFeedbackRequest request(Integer vote, String reason, String comment) {
        MessageFeedbackRequest req = new MessageFeedbackRequest();
        req.setVote(vote);
        req.setReason(reason);
        req.setComment(comment);
        return req;
    }
}
