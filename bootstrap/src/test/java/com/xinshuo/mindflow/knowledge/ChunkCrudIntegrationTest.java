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

package com.xinshuo.mindflow.knowledge;

import com.xinshuo.mindflow.MindflowApplication;
import com.xinshuo.mindflow.framework.convention.Result;
import com.xinshuo.mindflow.infra.embedding.EmbeddingService;
import com.xinshuo.mindflow.infra.embedding.RoutingEmbeddingService;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chunk 管理集成测试
 * <p>
 * 通过 @TestConfiguration + @Primary 提供假 EmbeddingService，
 * 覆盖 RoutingEmbeddingService，避免调真实 Embedding API。
 */
@Sql(scripts = "/knowledge-test-schema.sql",
     executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { MindflowApplication.class, ChunkCrudIntegrationTest.FakeEmbeddingConfig.class },
        properties = "spring.main.allow-bean-definition-overriding=true"
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChunkCrudIntegrationTest {

    @TestConfiguration
    static class FakeEmbeddingConfig {

        @Bean
        EmbeddingService routingEmbeddingService() {
            return new EmbeddingService() {
                private final List<Float> vec = List.of(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f);
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

    private static String token;
    private static String kbId;
    private static String docId;
    private static String chunkId;

    private String url(String path) {
        return "http://localhost:" + port + "/api/mindflow" + path;
    }

    private String suffix() {
        return String.valueOf(System.currentTimeMillis()).substring(7);
    }

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
        String s = suffix();
        h.set("Authorization", token);
        String kbBody = String.format("{\"name\":\"Chunk测试KB-%s\",\"embeddingModel\":\"fake\",\"collectionName\":\"ck-%s\"}", s, s);
        r = restTemplate.exchange(url("/knowledge-base"), HttpMethod.POST, new HttpEntity<>(kbBody, h), Result.class);
        assertTrue(r.getBody().isSuccess());
        kbId = (String) r.getBody().getData();

        // 上传 MD
        String md = "# 标题\n\n段落内容。\n\n## 功能\n\n- A\n- B\n\n|列1|列2|\n|---|---|\n|v1|v2|\n";
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(md.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "test.md"; }
        });
        form.add("sourceType", "file"); form.add("processMode", "chunk"); form.add("chunkStrategy", "structure_aware");
        h = new HttpHeaders(); h.setContentType(MediaType.MULTIPART_FORM_DATA); h.set("Authorization", token);
        r = restTemplate.exchange(url("/knowledge-base/" + kbId + "/docs/upload"), HttpMethod.POST, new HttpEntity<>(form, h), Result.class);
        assertTrue(r.getBody().isSuccess());
        @SuppressWarnings("unchecked") var doc = (Map<String, Object>) r.getBody().getData();
        docId = (String) doc.get("id");
        System.out.println("[1] setup done, kbId=" + kbId + ", docId=" + docId);
    }

    @Test @Order(2)
    void shouldStartChunk() {
        HttpHeaders h = new HttpHeaders(); h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/knowledge-base/docs/" + docId + "/chunk"), HttpMethod.POST, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess(), "分块应成功: " + r.getBody().getMessage());
        System.out.println("[2] 分块完成");
    }

    @Test @Order(3)
    void shouldListChunks() {
        HttpHeaders h = new HttpHeaders(); h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/knowledge-base/docs/" + docId + "/chunks?current=1&size=20"), HttpMethod.GET, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess());
        @SuppressWarnings("unchecked") var page = (Map<String, Object>) r.getBody().getData();
        @SuppressWarnings("unchecked") var records = (List<Map<String, Object>>) page.get("records");
        assertNotNull(records);
        assertFalse(records.isEmpty(), "分块后应有chunk记录");
        chunkId = (String) records.get(0).get("id");
        assertNotNull(records.get(0).get("content"));
        assertNotNull(records.get(0).get("contentHash"));
        System.out.println("[3] " + page.get("total") + " chunks, id=" + chunkId);
    }

    @Test @Order(4)
    void shouldFilterByEnabled() {
        HttpHeaders h = new HttpHeaders(); h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/knowledge-base/docs/" + docId + "/chunks?current=1&size=20&enabled=1"), HttpMethod.GET, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess());
        System.out.println("[4] enabled filter OK");
    }

    @Test @Order(5)
    void shouldCreateChunkManually() {
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(url("/knowledge-base/docs/" + docId + "/chunks"),
                HttpMethod.POST, new HttpEntity<>("{\"content\":\"手动新增chunk\",\"index\":999}", h), Result.class);
        assertTrue(r.getBody().isSuccess(), "手动新增应成功: " + r.getBody().getMessage());
        @SuppressWarnings("unchecked") var c = (Map<String, Object>) r.getBody().getData();
        assertEquals("手动新增chunk", c.get("content"));
        System.out.println("[5] 新增chunk: id=" + c.get("id"));
    }

    @Test @Order(6)
    void shouldUpdateChunk() {
        assertNotNull(chunkId);
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(url("/knowledge-base/docs/" + docId + "/chunks/" + chunkId),
                HttpMethod.PUT, new HttpEntity<>("{\"content\":\"已修改\"}", h), Result.class);
        assertTrue(r.getBody().isSuccess(), "更新应成功");
        System.out.println("[6] 更新完成");
    }

    @Test @Order(7)
    void shouldDisableAndEnableChunk() {
        assertNotNull(chunkId);
        HttpHeaders h = new HttpHeaders(); h.set("Authorization", token);
        ResponseEntity<Result> r1 = restTemplate.exchange(
                url("/knowledge-base/docs/" + docId + "/chunks/" + chunkId + "/enable?value=false"),
                HttpMethod.PATCH, new HttpEntity<>(h), Result.class);
        assertTrue(r1.getBody().isSuccess(), "禁用应成功");
        ResponseEntity<Result> r2 = restTemplate.exchange(
                url("/knowledge-base/docs/" + docId + "/chunks/" + chunkId + "/enable?value=true"),
                HttpMethod.PATCH, new HttpEntity<>(h), Result.class);
        assertTrue(r2.getBody().isSuccess(), "启用应成功");
        System.out.println("[7] 禁用/启用切换OK");
    }

    @Test @Order(8)
    void shouldDeleteChunk() {
        assertNotNull(chunkId);
        HttpHeaders h = new HttpHeaders(); h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/knowledge-base/docs/" + docId + "/chunks/" + chunkId),
                HttpMethod.DELETE, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess(), "删除应成功");
        System.out.println("[8] 删除完成");
    }

    @Test @Order(9)
    void shouldRejectEmptyContent() {
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(url("/knowledge-base/docs/" + docId + "/chunks"),
                HttpMethod.POST, new HttpEntity<>("{\"index\":1}", h), Result.class);
        assertFalse(r.getBody().isSuccess(), "空content应拒绝");
        System.out.println("[9] 空content拒绝: " + r.getBody().getMessage());
    }

    @Test @Order(10)
    void shouldGetDocStatusAfterChunk() {
        HttpHeaders h = new HttpHeaders(); h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/knowledge-base/docs/" + docId), HttpMethod.GET, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess());
        @SuppressWarnings("unchecked") var doc = (Map<String, Object>) r.getBody().getData();
        assertEquals("success", doc.get("status"), "状态应为success");
        assertTrue(((Number) doc.get("chunkCount")).intValue() > 0);
        System.out.println("[10] status=" + doc.get("status") + ", chunks=" + doc.get("chunkCount"));
    }
}
