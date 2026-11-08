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

package com.xinshuo.mindflow.rag.eval;

import com.xinshuo.mindflow.MindflowApplication;
import com.xinshuo.mindflow.framework.convention.Result;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 效果评测接口集成测试（第 20 步）
 *
 * <p>验证 {@code GET /rag/eval} 返回纯检索证据：改写子问题 → 意图 Top-1 → 检索结果。
 * 依赖真实 LLM（改写 + 意图分类），弱断言保证稳定。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvalIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static String token;

    private String url(String path) {
        return "http://localhost:" + port + "/api/mindflow" + path;
    }

    /**
     * 构造带 question 查询参数的 URI，避免字符串拼接导致二次 URL 编码
     */
    private java.net.URI evalUri(String question) {
        return org.springframework.web.util.UriComponentsBuilder
                .fromHttpUrl(url("/rag/eval"))
                .queryParam("question", question)
                .build()
                .encode()
                .toUri();
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
    void shouldReturnRetrievalEvidence() {
        assertNotNull(token);

        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);

        ResponseEntity<Result> r = restTemplate.exchange(
                evalUri("入职需要准备什么材料"),
                HttpMethod.GET, new HttpEntity<>(h), Result.class);

        assertTrue(r.getBody().isSuccess(), "评测接口应成功: " + r.getBody().getMessage());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) r.getBody().getData();
        assertNotNull(data, "评测结果不应为 null");

        // 改写 + 意图是链路必经步骤，子问题列表不应为空
        @SuppressWarnings("unchecked")
        var subIntents = (List<String>) data.get("subIntents");
        assertNotNull(subIntents, "subIntents 不应为 null");
        assertFalse(subIntents.isEmpty(), "改写后应至少有一个子问题");

        // 检索证据字段结构完整（无知识库数据时为空列表/空串，但字段本身不能缺失）
        assertNotNull(data.get("retrievedDocIds"));
        assertNotNull(data.get("retrievedChunkIds"));
        assertNotNull(data.get("retrievedContexts"));
        assertNotNull(data.get("retrievedContextDocIds"));
        assertNotNull(data.get("intentLeafIds"));
        assertTrue(((Number) data.get("latencyMs")).longValue() >= 0, "耗时不应为负");

        System.out.println("[2] 评测结果: subIntents=" + subIntents
                + ", intentLeafIds=" + data.get("intentLeafIds")
                + ", chunks=" + ((List<?>) data.get("retrievedChunkIds")).size()
                + ", hasKb=" + data.get("hasKb")
                + ", hasMcp=" + data.get("hasMcp")
                + ", latency=" + data.get("latencyMs") + "ms");
    }

    @Test
    @Order(3)
    void shouldKeepContextDocIdsAlignedWithContexts() {
        assertNotNull(token);

        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);

        ResponseEntity<Result> r = restTemplate.exchange(
                evalUri("请假流程是什么"),
                HttpMethod.GET, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) r.getBody().getData();
        List<?> contexts = (List<?>) data.get("retrievedContexts");
        List<?> contextDocIds = (List<?>) data.get("retrievedContextDocIds");
        assertEquals(contexts.size(), contextDocIds.size(),
                "retrievedContextDocIds 必须与 retrievedContexts 等长（chunk 维度按 index 对齐）");
        System.out.println("[3] context 与 docId 对齐 OK, size=" + contexts.size());
    }
}
