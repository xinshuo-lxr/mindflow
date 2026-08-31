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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 深度思考集成测试（第 14 步）
 *
 * <p>测试链路：登录 → 发起 deepThinking=true 的 SSE 对话 → 校验
 * META/message(finish)/done 事件齐全，且 completion 事件可能携带 thinking 相关结构。
 *
 * <p>使用真实 LLM（qwen3-max 支持推理），弱断言保证测试稳定。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeepThinkingChatIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static String token;

    private String url(String path) {
        return "http://localhost:" + port + "/api/mindflow" + path;
    }

    @Test
    @Order(1)
    void shouldLogin() {
        String body = "{\"username\":\"admin\",\"password\":\"admin\"}";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/auth/login"), HttpMethod.POST, new HttpEntity<>(body, h), Result.class);
        assertTrue(r.getBody().isSuccess());
        @SuppressWarnings("unchecked")
        var d = (Map<String, Object>) r.getBody().getData();
        token = (String) d.get("token");
        System.out.println("[1] 登录成功");
    }

    @Test
    @Order(2)
    void shouldStreamWithDeepThinking() throws Exception {
        assertNotNull(token);

        String body = "{\"message\":\"1+1等于几？请给出思考过程\",\"deepThinking\":true}";

        RestTemplate streamingTemplate = new RestTemplate();
        final StringBuilder fullOutput = new StringBuilder();
        streamingTemplate.execute(url("/chat/send-stream"), HttpMethod.POST,
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
                            fullOutput.append(line).append("\n");
                            if (line.startsWith("event:done")) break;
                        }
                    }
                    return null;
                });

        String sse = fullOutput.toString();
        System.out.println("[2] deepThinking SSE 输出长度=" + sse.length());
        System.out.println("[2] deepThinking SSE 输出内容" + sse);

        // 核心事件必须齐全
        assertTrue(sse.contains("event:meta"), "应包含 meta 事件");
        assertTrue(sse.contains("event:message"), "应包含 message 增量事件");
        assertTrue(sse.contains("event:finish"), "应包含 finish 完成事件");
        assertTrue(sse.contains("event:done"), "应包含 done 结束事件");

        // deepThinking=true 时，模型可能产出 think 类型的增量（真实 LLM 软断言）
        boolean hasThink = sse.contains("\"type\":\"think\"") || sse.contains("type\":\"think");
        System.out.println("[2] 收到 think 类型增量: " + hasThink);
    }

    @Test
    @Order(3)
    void shouldStreamWithoutDeepThinking() throws Exception {
        assertNotNull(token);

        // deepThinking 缺省（null）应走普通对话，同样正常完成
        String body = "{\"message\":\"你好\"}";

        RestTemplate streamingTemplate = new RestTemplate();
        final StringBuilder fullOutput = new StringBuilder();
        streamingTemplate.execute(url("/chat/send-stream"), HttpMethod.POST,
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
                            fullOutput.append(line).append("\n");
                            if (line.startsWith("event:done")) break;
                        }
                    }
                    return null;
                });

        String sse = fullOutput.toString();
        assertTrue(sse.contains("event:meta"), "应包含 meta 事件");
        assertTrue(sse.contains("event:message"), "应包含 message 增量事件");
        assertTrue(sse.contains("event:done"), "应包含 done 结束事件");
        System.out.println("[3] 普通对话 SSE 正常完成");
    }
}
