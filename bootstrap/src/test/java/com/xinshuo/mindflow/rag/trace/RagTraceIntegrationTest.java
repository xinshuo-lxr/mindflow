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

package com.xinshuo.mindflow.rag.trace;

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
 * 全链路 Trace 集成测试（第 18 步）
 *
 * <p>发起一次 SSE 对话 → 验证 t_rag_trace_run 落库（链路）+ t_rag_trace_node 落库（节点，含用户首包 TTFT）
 * → 验证 /rag/traces 查询接口。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RagTraceIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static String token;
    private static String conversationId;
    private static String traceId;

    private String url(String path) {
        return "http://localhost:" + port + "/api/mindflow" + path;
    }

    private String suffix() {
        return String.valueOf(System.currentTimeMillis()).substring(7);
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
    void shouldRecordTraceForChat() throws Exception {
        assertNotNull(token);
        conversationId = "trace" + suffix();

        String body = String.format("{\"message\":\"你好，请简单介绍下自己\",\"conversationId\":\"%s\"}", conversationId);

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

        assertTrue(fullOutput.toString().contains("event:done"), "SSE 应正常结束");

        // 等 run 终态收尾（finishRun 在 onComplete 之后执行）
        Thread.sleep(2000);

        // 按 conversationId 查 run
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/rag/traces/runs?current=1&size=10&conversationId=" + conversationId),
                HttpMethod.GET, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess(), "查询 trace runs 应成功");

        @SuppressWarnings("unchecked")
        var page = (Map<String, Object>) r.getBody().getData();
        @SuppressWarnings("unchecked")
        var records = (List<Map<String, Object>>) page.get("records");

        assertNotNull(records, "records 不应为 null");
        assertFalse(records.isEmpty(), "对话后应有 trace run 记录");

        Map<String, Object> run = records.get(0);
        traceId = (String) run.get("traceId");
        assertNotNull(traceId, "应存在 traceId");
        assertEquals("SUCCESS", run.get("status"), "run 状态应为 SUCCESS");
        assertEquals("rag-stream-chat", run.get("traceName"), "链路名称应为 rag-stream-chat");
        System.out.println("[2] trace run 记录: traceId=" + traceId + ", status=" + run.get("status")
                + ", duration=" + run.get("durationMs") + "ms, ttft=" + run.get("ttftMs") + "ms");
    }

    @Test
    @Order(3)
    void shouldRecordNodesWithHierarchy() {
        assertNotNull(traceId, "需要先完成对话产生 trace");

        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/rag/traces/runs/" + traceId + "/nodes"), HttpMethod.GET, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess());

        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) r.getBody().getData();
        assertNotNull(nodes, "nodes 不应为 null");
        assertFalse(nodes.isEmpty(), "应记录 trace 节点");

        // 应存在用户首包 TTFT 节点（USER_TTFT）
        boolean hasUserTtft = nodes.stream().anyMatch(n -> "USER_TTFT".equals(n.get("nodeType")));
        assertTrue(hasUserTtft, "应记录 USER_TTFT（用户感知首包）节点");

        // 每个节点都应有 traceId / status / depth
        for (Map<String, Object> node : nodes) {
            assertNotNull(node.get("nodeId"), "节点应有 nodeId");
            assertNotNull(node.get("status"), "节点应有 status");
            assertNotNull(node.get("depth"), "节点应有 depth");
        }
        System.out.println("[3] trace 节点数: " + nodes.size() + ", 含 USER_TTFT: " + hasUserTtft);
        nodes.forEach(n -> System.out.println("    - [" + n.get("nodeType") + "] " + n.get("nodeName")
                + " depth=" + n.get("depth") + " status=" + n.get("status") + " " + n.get("durationMs") + "ms"));
    }

    @Test
    @Order(4)
    void shouldQueryTraceDetail() {
        assertNotNull(traceId);

        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/rag/traces/runs/" + traceId), HttpMethod.GET, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess());

        @SuppressWarnings("unchecked")
        var detail = (Map<String, Object>) r.getBody().getData();
        assertNotNull(detail.get("run"), "详情应包含 run");
        assertNotNull(detail.get("nodes"), "详情应包含 nodes");
        System.out.println("[4] trace 详情查询 OK");
    }
}
