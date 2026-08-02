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

package com.xinshuo.mindflow.rag;

import cn.hutool.json.JSONUtil;
import com.xinshuo.mindflow.MindflowApplication;
import com.xinshuo.mindflow.framework.convention.Result;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话模块集成测试——启动完整 Spring 容器，真实 HTTP 请求。
 *
 * <p>前置条件：
 * <ol>
 *   <li>init_data_pg.sql 已执行（admin 用户存在）</li>
 * </ol>
 *
 * <p>测试链路：登录 → 发消息创建会话 → 查会话列表 → 查消息历史 → 重命名 → 删除
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConversationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static String token;
    private static String conversationId;

    private String url(String path) {
        return "http://localhost:" + port + "/api/mindflow" + path;
    }

    @Test
    @Order(1)
    void shouldLoginSuccessfully() {
        String body = """
                {
                    "username": "admin",
                    "password": "admin"
                }
                """;

        ResponseEntity<Result> response = post("/auth/login", body, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "登录应成功: " + result.getMessage());

        var data = (Map<String, Object>) result.getData();
        token = (String) data.get("token");
        assertNotNull(token, "token 不应为空");

        System.out.println("登录成功, token: " + token);
    }

    @Test
    @Order(2)
    void shouldCreateConversationViaChat() throws Exception {
        assertNotNull(token, "需要先登录获取 token");

        String body = """
                {
                    "message": "你好，测试消息"
                }
                """;

        // 用 RestTemplate 发 SSE 请求，读取 SSE 流提取 conversationId
        RestTemplate streamingTemplate = new RestTemplate();
        String sseUrl = url("/chat/send-stream");

        streamingTemplate.execute(sseUrl, HttpMethod.POST,
                req -> {
                    req.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    req.getHeaders().set("Authorization", token);
                    req.getBody().write(body.getBytes(StandardCharsets.UTF_8));
                },
                response -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            System.out.println("SSE: " + line);
                            // 提取 META 事件中的 conversationId
                            if (line.startsWith("event:meta")) {
                                // 下一行是 data
                                String dataLine = reader.readLine();
                                if (dataLine != null && dataLine.startsWith("data:")) {
                                    String json = dataLine.substring(5).trim();
                                    Map<String, Object> meta = JSONUtil.toBean(json, Map.class);
                                    conversationId = (String) meta.get("conversationId");
                                    System.out.println("获取到 conversationId: " + conversationId);
                                    continue;
                                }
                            }
                            // finish 携带完成数据，done 才是服务端声明的流结束标志。
                            if (line.startsWith("event:done")) {
                                break;
                            }
                        }
                    }
                    return null;
                });

        assertNotNull(conversationId, "应从 SSE 流中获取到 conversationId");
    }

    @Test
    @Order(3)
    void shouldListConversations() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(conversationId, "需要先创建会话");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Result> response = restTemplate.exchange(
                url("/conversations"), HttpMethod.GET, request, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "获取会话列表应成功: " + result.getMessage());

        List<Map<String, Object>> conversations = (List<Map<String, Object>>) result.getData();
        assertNotNull(conversations, "会话列表不应为 null");
        assertFalse(conversations.isEmpty(), "会话列表不应为空");

        // 验证包含刚创建的会话
        boolean found = conversations.stream()
                .anyMatch(c -> conversationId.equals(c.get("conversationId")));
        assertTrue(found, "会话列表应包含刚创建的会话");

        System.out.println("会话列表共 " + conversations.size() + " 条，包含会话: " + conversationId);
    }

    @Test
    @Order(4)
    void shouldListMessages() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(conversationId, "需要先创建会话");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Result> response = restTemplate.exchange(
                url("/conversations/" + conversationId + "/messages"),
                HttpMethod.GET, request, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "获取消息列表应成功: " + result.getMessage());

        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.getData();
        assertNotNull(messages, "消息列表不应为 null");
        assertFalse(messages.isEmpty(), "消息列表不应为空（至少包含 user 和 assistant）");

        // 验证消息角色
        Map<String, Object> firstMsg = messages.get(0);
        assertEquals("user", firstMsg.get("role"), "第一条消息应为 user");
        assertNotNull(firstMsg.get("content"), "消息内容不应为空");

        System.out.println("消息列表共 " + messages.size() + " 条");
    }

    @Test
    @Order(5)
    void shouldRenameConversation() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(conversationId, "需要先创建会话");

        String body = """
                {
                    "title": "测试重命名会话"
                }
                """;

        ResponseEntity<Result> response = put(
                "/conversations/" + conversationId, body, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "重命名会话应成功: " + result.getMessage());

        System.out.println("会话重命名成功: " + conversationId);
    }

    @Test
    @Order(6)
    void shouldDeleteConversation() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(conversationId, "需要先创建会话");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Result> response = restTemplate.exchange(
                url("/conversations/" + conversationId),
                HttpMethod.DELETE, request, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "删除会话应成功: " + result.getMessage());

        System.out.println("会话删除成功: " + conversationId);
    }

    @Test
    @Order(7)
    void shouldReturnEmptyForDeletedConversationMessages() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(conversationId, "需要先创建会话");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Result> response = restTemplate.exchange(
                url("/conversations/" + conversationId + "/messages"),
                HttpMethod.GET, request, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess());

        // 删除后消息列表应为空
        List<?> messages = (List<?>) result.getData();
        assertTrue(messages == null || messages.isEmpty(),
                "已删除会话的消息列表应为空: " + messages);

        System.out.println("已删除会话的消息列表为空，验证通过");
    }

    // ==================== 辅助方法 ====================

    private ResponseEntity<Result> post(String path, String body, Class<Result> clazz) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        return restTemplate.exchange(url(path), HttpMethod.POST, request, clazz);
    }

    private ResponseEntity<Result> put(String path, String body, Class<Result> clazz) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", token);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        return restTemplate.exchange(url(path), HttpMethod.PUT, request, clazz);
    }
}

