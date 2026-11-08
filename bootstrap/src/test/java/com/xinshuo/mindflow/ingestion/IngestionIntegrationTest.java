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

package com.xinshuo.mindflow.ingestion;

import com.xinshuo.mindflow.MindflowApplication;
import com.xinshuo.mindflow.framework.convention.Result;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 摄取流水线集成测试（第 22 步）
 *
 * <p>覆盖：流水线 CRUD 与校验 → 上传文档执行 fetcher→parser 链路 → 任务与节点日志落库。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IngestionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static String token;
    private static String pipelineId;
    private static String taskId;

    private String url(String path) {
        return "http://localhost:" + port + "/api/mindflow" + path;
    }

    private String suffix() {
        return String.valueOf(System.currentTimeMillis()).substring(7);
    }

    private HttpHeaders authJson() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", token);
        return h;
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return h;
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
    void shouldCreatePipeline() {
        String name = "ingest-" + suffix();
        String body = String.format("""
                {
                  "name": "%s",
                  "description": "集成测试流水线",
                  "nodes": [
                    {"nodeId":"fetch","nodeType":"fetcher","nextNodeId":"parse"},
                    {"nodeId":"parse","nodeType":"parser","settings":{"rules":[{"mimeType":"ALL"}]}}
                  ]
                }
                """, name);

        ResponseEntity<Result> r = restTemplate.exchange(
                url("/ingestion/pipelines"), HttpMethod.POST, new HttpEntity<>(body, authJson()), Result.class);
        assertTrue(r.getBody().isSuccess(), "创建流水线应成功: " + r.getBody().getMessage());

        @SuppressWarnings("unchecked")
        var vo = (Map<String, Object>) r.getBody().getData();
        pipelineId = (String) vo.get("id");
        assertNotNull(pipelineId);
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) vo.get("nodes");
        assertEquals(2, nodes.size(), "应持久化 2 个节点");
        System.out.println("[2] 创建流水线: " + pipelineId);
    }

    @Test
    @Order(3)
    void shouldRejectUnknownNodeType() {
        String body = String.format("""
                {"name":"bad-%s","nodes":[{"nodeId":"x","nodeType":"not-a-node"}]}
                """, suffix());
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/ingestion/pipelines"), HttpMethod.POST, new HttpEntity<>(body, authJson()), Result.class);
        assertFalse(r.getBody().isSuccess(), "未知节点类型应被拒绝");
        assertTrue(r.getBody().getMessage().contains("未知节点类型"));
        System.out.println("[3] 未知节点类型拒绝 OK");
    }

    @Test
    @Order(4)
    void shouldQueryAndPagePipelines() {
        ResponseEntity<Result> get = restTemplate.exchange(
                url("/ingestion/pipelines/" + pipelineId), HttpMethod.GET, new HttpEntity<>(auth()), Result.class);
        assertTrue(get.getBody().isSuccess());
        @SuppressWarnings("unchecked")
        var vo = (Map<String, Object>) get.getBody().getData();
        assertNotNull(vo.get("name"));

        ResponseEntity<Result> page = restTemplate.exchange(
                url("/ingestion/pipelines?pageNo=1&pageSize=10"), HttpMethod.GET, new HttpEntity<>(auth()), Result.class);
        assertTrue(page.getBody().isSuccess());
        @SuppressWarnings("unchecked")
        var p = (Map<String, Object>) page.getBody().getData();
        @SuppressWarnings("unchecked")
        var records = (List<Map<String, Object>>) p.get("records");
        assertFalse(records.isEmpty(), "分页应至少包含刚创建的流水线");
        System.out.println("[4] 查询/分页 OK, total=" + p.get("total"));
    }

    @Test
    @Order(5)
    void shouldUploadAndExecutePipeline() {
        String md = "# 摄取测试\n\n这是一段用于验证 fetcher→parser 链路的文本。\n";
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(md.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "ingest-test.md";
            }
        });

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.set("Authorization", token);

        ResponseEntity<Result> r = restTemplate.exchange(
                url("/ingestion/tasks/upload?pipelineId=" + pipelineId),
                HttpMethod.POST, new HttpEntity<>(form, h), Result.class);

        assertTrue(r.getBody().isSuccess(), "上传执行应成功: " + r.getBody().getMessage());
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) r.getBody().getData();
        taskId = (String) result.get("taskId");
        assertEquals("completed", result.get("status"), "流水线应执行完成");
        System.out.println("[5] 任务执行完成: " + taskId + ", status=" + result.get("status"));
    }

    @Test
    @Order(6)
    void shouldPersistTaskAndNodeLogs() {
        assertNotNull(taskId);
        ResponseEntity<Result> get = restTemplate.exchange(
                url("/ingestion/tasks/" + taskId), HttpMethod.GET, new HttpEntity<>(auth()), Result.class);
        assertTrue(get.getBody().isSuccess());
        @SuppressWarnings("unchecked")
        var task = (Map<String, Object>) get.getBody().getData();
        assertEquals("completed", task.get("status"));
        assertEquals(pipelineId, task.get("pipelineId"));

        ResponseEntity<Result> nodes = restTemplate.exchange(
                url("/ingestion/tasks/" + taskId + "/nodes"), HttpMethod.GET, new HttpEntity<>(auth()), Result.class);
        assertTrue(nodes.getBody().isSuccess());
        @SuppressWarnings("unchecked")
        var nodeList = (List<Map<String, Object>>) nodes.getBody().getData();
        assertEquals(2, nodeList.size(), "fetcher/parser 两条节点记录");
        assertTrue(nodeList.stream().allMatch(n -> "success".equals(n.get("status"))),
                "两个节点都应成功");
        assertEquals("fetch", nodeList.get(0).get("nodeId"), "节点应按链路顺序记录");
        System.out.println("[6] 任务/节点日志落库 OK, nodes=" + nodeList.size());
    }

    @Test
    @Order(7)
    void shouldUpdateAndDeletePipeline() {
        String body = String.format("""
                {"name":"ingest-updated-%s","nodes":[{"nodeId":"parse","nodeType":"parser"}]}
                """, suffix());
        ResponseEntity<Result> update = restTemplate.exchange(
                url("/ingestion/pipelines/" + pipelineId), HttpMethod.PUT,
                new HttpEntity<>(body, authJson()), Result.class);
        assertTrue(update.getBody().isSuccess(), "更新应成功");
        @SuppressWarnings("unchecked")
        var vo = (Map<String, Object>) update.getBody().getData();
        assertEquals(1, ((List<?>) vo.get("nodes")).size(), "更新后应只剩 1 个节点");

        ResponseEntity<Result> delete = restTemplate.exchange(
                url("/ingestion/pipelines/" + pipelineId), HttpMethod.DELETE,
                new HttpEntity<>(auth()), Result.class);
        assertTrue(delete.getBody().isSuccess(), "删除应成功");
        System.out.println("[7] 更新/删除流水线 OK");
    }
}
