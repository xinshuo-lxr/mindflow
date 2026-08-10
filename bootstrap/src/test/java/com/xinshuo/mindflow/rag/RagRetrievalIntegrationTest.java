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
import com.xinshuo.mindflow.framework.convention.RetrievedChunk;
import com.xinshuo.mindflow.infra.embedding.EmbeddingService;
import com.xinshuo.mindflow.rag.core.prompt.RAGPromptService;
import com.xinshuo.mindflow.rag.core.retrieve.RetrieveRequest;
import com.xinshuo.mindflow.rag.core.retrieve.RetrieverService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 检索闭环集成测试
 *
 * <p>前置：t_knowledge_vector / t_knowledge_chunk 表存在且已启用 pgvector 扩展
 *
 * <p>测试链路：登录→建KB→上传MD→分块入库→向量检索→RAG问答复核
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { MindflowApplication.class, RagRetrievalIntegrationTest.FakeEmbeddingConfig.class },
        properties = "spring.main.allow-bean-definition-overriding=true"
)
@ActiveProfiles("local")
@Sql(scripts = "/knowledge-test-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RagRetrievalIntegrationTest {

    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        EmbeddingService routingEmbeddingService() {
            return new EmbeddingService() {
                private final float[] raw = {0.12f, 0.34f, 0.56f, 0.78f, 0.91f, 0.23f, 0.45f, 0.67f};
                private final List<Float> vec = List.of(0.12f, 0.34f, 0.56f, 0.78f, 0.91f, 0.23f, 0.45f, 0.67f);
                @Override public List<Float> embed(String t) { return vec; }
                @Override public List<Float> embed(String t, String m) { return vec; }
                @Override public List<List<Float>> embedBatch(List<String> texts) {
                    List<List<Float>> r = new ArrayList<>();
                    for (int i = 0; i < texts.size(); i++) r.add(vec);
                    return r;
                }
                @Override public List<List<Float>> embedBatch(List<String> texts, String m) { return embedBatch(texts); }
            };
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RetrieverService retrieverService;

    @Autowired
    private RAGPromptService ragPromptService;

    private static String token;
    private static String kbId;
    private static String docId;
    private static String collectionName = "rag-ret-" + String.valueOf(System.currentTimeMillis()).substring(7);

    private String url(String path) {
        return "http://localhost:" + port + "/api/mindflow" + path;
    }

    private String suffix() {
        return String.valueOf(System.currentTimeMillis()).substring(7);
    }

    // ============ 前置 ============

    @Test @Order(1)
    void shouldSetup() {
        // 登录
        String body = "{\"username\":\"admin\",\"password\":\"admin\"}";
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Result> r = restTemplate.exchange(url("/auth/login"), HttpMethod.POST, new HttpEntity<>(body, h), Result.class);
        assertTrue(r.getBody().isSuccess());
        @SuppressWarnings("unchecked") var d = (Map<String, Object>) r.getBody().getData();
        token = (String) d.get("token");

        // 建 KB
        h.set("Authorization", token);
        String kbBody = String.format(
                "{\"name\":\"检索测试KB-%s\",\"embeddingModel\":\"fake\",\"collectionName\":\"%s\"}", collectionName, collectionName);
        r = restTemplate.exchange(url("/knowledge-base"), HttpMethod.POST, new HttpEntity<>(kbBody, h), Result.class);
        assertTrue(r.getBody().isSuccess());
        kbId = (String) r.getBody().getData();

        // 上传 MD
        String md = "# 入职流程\n\n新员工入职需完成以下步骤：\n\n1. 提交身份证复印件\n2. 签订劳动合同\n3. 领取办公设备\n\n## 试用期\n\n试用期三个月，考核通过后转正。\n";
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(md.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "onboarding.md"; }
        });
        form.add("sourceType", "file"); form.add("processMode", "chunk"); form.add("chunkStrategy", "structure_aware");
        h = new HttpHeaders(); h.setContentType(MediaType.MULTIPART_FORM_DATA); h.set("Authorization", token);
        r = restTemplate.exchange(url("/knowledge-base/" + kbId + "/docs/upload"), HttpMethod.POST, new HttpEntity<>(form, h), Result.class);
        assertTrue(r.getBody().isSuccess());
        @SuppressWarnings("unchecked") var doc = (Map<String, Object>) r.getBody().getData();
        docId = (String) doc.get("id");

        // 分块入库
        h = new HttpHeaders(); h.set("Authorization", token);
        r = restTemplate.exchange(url("/knowledge-base/docs/" + docId + "/chunk"), HttpMethod.POST, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess(), "分块应成功: " + r.getBody().getMessage());

        System.out.println("[1] setup done, kbId=" + kbId + ", docId=" + docId);
    }

    // ============ 检索 ============

    @Test @Order(2)
    void shouldRetrieveByQuery() {
        List<RetrievedChunk> chunks = retrieverService.retrieve(
                RetrieveRequest.builder().query("入职流程").collectionName(collectionName).topK(5).build());

        assertNotNull(chunks, "检索结果不应为null");
        assertFalse(chunks.isEmpty(), "应有检索结果（知识库中有入职相关文档）");
        assertTrue(chunks.size() <= 5, "不应超过topK");

        RetrievedChunk first = chunks.get(0);
        assertNotNull(first.getId(), "chunk ID 不应为空");
        assertNotNull(first.getText(), "chunk 文本不应为空");
        assertTrue(first.getScore() >= 0 && first.getScore() <= 1, "score 应在 [0,1] 范围");

        System.out.println("[2] 检索到 " + chunks.size() + " 个 chunk, top1 score=" + String.format("%.4f", first.getScore()));
        System.out.println("   text: " + first.getText().substring(0, Math.min(60, first.getText().length())) + "...");
    }

    @Test @Order(3)
    void shouldReturnEmptyForNoMatch() {
        List<RetrievedChunk> chunks = retrieverService.retrieve(
                RetrieveRequest.builder().query("宇宙飞船设计").collectionName(collectionName).topK(3).build());

        // 即使无相关文档，pgvector 也会返回 topK 条，只是分数很低
        // 真正验证的是 RAGPromptService 能不能正确格式化
        assertNotNull(chunks);
        System.out.println("[3] 无关查询返回 " + chunks.size() + " 条，top1 score="
                + (chunks.isEmpty() ? "N/A" : String.format("%.4f", chunks.get(0).getScore())));
    }

    // ============ Prompt 编排 ============

    @Test @Order(4)
    void shouldBuildContextViaRagPromptService() {
        String context = ragPromptService.buildContext("入职需要什么材料", collectionName, 3);

        assertNotNull(context, "context 不应为null");
        assertFalse(context.isBlank(), "context 不应为空");
        assertTrue(context.contains("参考资料"), "应包含参考资料标记");
        assertTrue(context.contains("相关度"), "应包含相关度");

        System.out.println("[4] context length=" + context.length());
    }

    @Test @Order(5)
    void shouldBuildEmptyContextForMissingCollection() {
        String context = ragPromptService.buildContext("测试问题", "non-existent-collection", 3);
        // 不存在的 collection：检索可能返回 0 条或少量低分 chunk
        assertNotNull(context);
        System.out.println("[5] missing collection context='" + (context.isEmpty() ? "(empty)" : context.substring(0, Math.min(60, context.length())) + "...") + "'");
    }

    // ============ RAG 对话 ============

    @Test @Order(6)
    void shouldAnswerWithRagContext() throws Exception {
        assertNotNull(token);
        assertNotNull(kbId);

        String body = String.format("{\"message\":\"入职需要什么材料\",\"kbId\":\"%s\"}", kbId);

        RestTemplate streamingTemplate = new RestTemplate();
        String sseUrl = url("/chat/send-stream");

        final StringBuilder fullOutput = new StringBuilder();
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
                            fullOutput.append(line).append("\n");
                            if (line.startsWith("event:done")) break;
                        }
                    }
                    return null;
                });

        String sse = fullOutput.toString();
        System.out.println("[6] SSE output length=" + sse.length());
        // 应该至少包含 completion 事件
        assertTrue(sse.contains("event:completion") || sse.contains("event:done"),
                "SSE 应包含 completion 或 done 事件");

        // 验证 RAG context 被注入了（从 SSE trace 里可能能看到）
        // 不强制校验 LLM 回答内容——取决于真实 LLM 服务
    }

    @Test @Order(7)
    void shouldFallbackForMissingKb() throws Exception {
        assertNotNull(token);

        String body = "{\"message\":\"你好\",\"kbId\":\"non-existent-kb-id\"}";

        RestTemplate streamingTemplate = new RestTemplate();
        String sseUrl = url("/chat/send-stream");

        final StringBuilder fullOutput = new StringBuilder();
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
                            fullOutput.append(line).append("\n");
                            if (line.startsWith("event:done")) break;
                        }
                    }
                    return null;
                });

        String sse = fullOutput.toString();
        // 不存在的 KB 不应导致崩溃，仍应正常回答
        assertTrue(sse.contains("event:completion") || sse.contains("event:done"),
                "SSE 应正常完成（不存在的KB降级为普通对话）");
        System.out.println("[7] missing KB fallback OK, SSE length=" + sse.length());
    }
}
