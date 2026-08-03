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
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 知识库模块集成测试——启动完整 Spring 容器，真实 HTTP 请求。
 *
 * <p>前置条件：
 * <ol>
 *   <li>init_data_pg.sql 已执行（admin 用户存在）</li>
 *   <li>t_knowledge_base / t_knowledge_document 表已创建</li>
 *   <li>Redis + PostgreSQL 已启动</li>
 * </ol>
 *
 * <p>测试链路：登录 → 创建知识库 → 查询 → 分页 → 重命名 → 上传文档 → 文档CRUD → 删除文档 → 删除知识库
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static String token;
    private static String kbId;
    private static String docId;

    /** 每次测试运行使用唯一后缀，避免脏数据冲突 */
    private static final String SUFFIX = String.valueOf(System.currentTimeMillis()).substring(7);

    private String url(String path) {
        return "http://localhost:" + port + "/api/mindflow" + path;
    }

    // ==================== 认证 ====================

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

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.getData();
        token = (String) data.get("token");
        assertNotNull(token, "token 不应为空");

        System.out.println("[1/13] 登录成功");
    }

    // ==================== 知识库 CRUD ====================

    @Test
    @Order(2)
    void shouldCreateKnowledgeBase() {
        assertNotNull(token, "需要先登录获取 token");

        String kbName = "集成测试知识库-" + SUFFIX;
        String collectionName = "test-kb-" + SUFFIX;
        String body = String.format("""
                {
                    "name": "%s",
                    "embeddingModel": "qwen3-embedding:8b-fp16",
                    "collectionName": "%s"
                }
                """, kbName, collectionName);

        ResponseEntity<Result> response = authPost("/knowledge-base", body, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "创建知识库应成功: " + result.getMessage());

        kbId = (String) result.getData();
        assertNotNull(kbId, "知识库 ID 不应为空");
        assertFalse(kbId.isBlank(), "知识库 ID 不应为空字符串");

        System.out.println("[2/13] 创建知识库成功, kbId=" + kbId);
    }

    @Test
    @Order(3)
    void shouldGetKnowledgeBaseById() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(kbId, "需要先创建知识库");

        ResponseEntity<Result> response = authGet("/knowledge-base/" + kbId, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "查询知识库详情应成功");

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.getData();
        assertEquals(("集成测试知识库-" + SUFFIX), data.get("name"));
        assertEquals("qwen3-embedding:8b-fp16", data.get("embeddingModel"));

        System.out.println("[3/13] 查询知识库详情成功, name=" + data.get("name"));
    }

    @Test
    @Order(4)
    void shouldPageQueryKnowledgeBases() {
        assertNotNull(token, "需要先登录获取 token");

        ResponseEntity<Result> response = authGet(
                "/knowledge-base?current=1&size=10&name=集成测试", Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "分页查询知识库应成功");

        @SuppressWarnings("unchecked")
        var pageData = (Map<String, Object>) result.getData();
        @SuppressWarnings("unchecked")
        var records = (List<Map<String, Object>>) pageData.get("records");
        assertNotNull(records);
        assertFalse(records.isEmpty(), "分页结果不应为空");

        boolean found = records.stream()
                .anyMatch(r -> kbId.equals(r.get("id")));
        assertTrue(found, "分页结果应包含刚创建的知识库");

        System.out.println("[4/13] 分页查询知识库成功, 共 " + pageData.get("total") + " 条");
    }

    @Test
    @Order(5)
    void shouldRenameKnowledgeBase() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(kbId, "需要先创建知识库");

        String newName = "集成测试知识库-已改名-" + SUFFIX;
        String body = String.format("""
                {
                    "id": "%s",
                    "name": "%s"
                }
                """, kbId, newName);

        ResponseEntity<Result> response = authPut("/knowledge-base/" + kbId, body, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "重命名知识库应成功: " + result.getMessage());

        // 验证已改名
        ResponseEntity<Result> getResponse = authGet("/knowledge-base/" + kbId, Result.class);
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) getResponse.getBody().getData();
        assertEquals(("集成测试知识库-已改名-" + SUFFIX), data.get("name"));

        System.out.println("[5/13] 重命名知识库成功");
    }

    // ==================== 文档 CRUD ====================

    @Test
    @Order(6)
    void shouldUploadDocument() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(kbId, "需要先创建知识库");

        // 构造一个 markdown 文件内容
        byte[] fileContent = "# 测试文档\n\n这是一个集成测试文档。\n\n## 第二段\n\n测试内容。".getBytes(StandardCharsets.UTF_8);
        String fileName = ("test-doc-" + SUFFIX + ".md");

        // 构造 multipart 请求
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(fileContent) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        body.add("sourceType", "file");
        body.add("processMode", "chunk");
        body.add("chunkStrategy", "structure_aware");
        body.add("chunkConfig", "{\"targetChars\":1400,\"maxChars\":1800,\"minChars\":600,\"overlapChars\":0}");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", token);

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Result> response = restTemplate.exchange(
                url("/knowledge-base/" + kbId + "/docs/upload"),
                HttpMethod.POST, request, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "上传文档应成功: " + result.getMessage());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.getData();
        docId = (String) data.get("id");
        assertNotNull(docId, "文档 ID 不应为空");
        assertEquals(fileName, data.get("docName"));
        assertEquals("markdown", data.get("fileType"));
        assertEquals("pending", data.get("status"));

        System.out.println("[6/13] 上传文档成功, docId=" + docId + ", fileType=" + data.get("fileType"));
    }

    @Test
    @Order(7)
    void shouldGetDocumentById() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(docId, "需要先上传文档");

        ResponseEntity<Result> response = authGet("/knowledge-base/docs/" + docId, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "查询文档详情应成功");

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.getData();
        assertEquals(docId, data.get("id"));
        assertEquals(("test-doc-" + SUFFIX + ".md"), data.get("docName"));
        assertEquals("markdown", data.get("fileType"));

        System.out.println("[7/13] 查询文档详情成功, docName=" + data.get("docName"));
    }

    @Test
    @Order(8)
    void shouldPageQueryDocuments() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(kbId, "需要先创建知识库");

        ResponseEntity<Result> response = authGet(
                "/knowledge-base/" + kbId + "/docs?current=1&size=10", Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "分页查询文档应成功");

        @SuppressWarnings("unchecked")
        var pageData = (Map<String, Object>) result.getData();
        @SuppressWarnings("unchecked")
        var records = (List<Map<String, Object>>) pageData.get("records");
        assertNotNull(records);
        assertFalse(records.isEmpty(), "文档分页结果不应为空");

        boolean found = records.stream()
                .anyMatch(r -> docId.equals(r.get("id")));
        assertTrue(found, "分页结果应包含刚上传的文档");

        System.out.println("[8/13] 分页查询文档成功, 共 " + pageData.get("total") + " 条");
    }

    @Test
    @Order(9)
    void shouldSearchDocuments() {
        assertNotNull(token, "需要先登录获取 token");

        ResponseEntity<Result> response = authGet(
                "/knowledge-base/docs/search?keyword=test-doc&limit=5", Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "搜索文档应成功");

        @SuppressWarnings("unchecked")
        var records = (List<Map<String, Object>>) result.getData();
        assertNotNull(records);
        assertFalse(records.isEmpty(), "搜索结果不应为空");

        boolean found = records.stream()
                .anyMatch(r -> docId.equals(r.get("id")));
        assertTrue(found, "搜索结果应包含上传的文档");

        // 验证 kbName 已填充
        Map<String, Object> foundDoc = records.stream()
                .filter(r -> docId.equals(r.get("id")))
                .findFirst().orElseThrow();
        assertNotNull(foundDoc.get("kbName"), "搜索结果应包含知识库名称");

        System.out.println("[9/13] 搜索文档成功, 共 " + records.size() + " 条, kbName=" + foundDoc.get("kbName"));
    }

    @Test
    @Order(10)
    void shouldUpdateDocumentName() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(docId, "需要先上传文档");

        String newDocName = "测试文档-已改名-" + SUFFIX + ".md";
        String body = String.format("""
                {
                    "docName": "%s"
                }
                """, newDocName);

        ResponseEntity<Result> response = authPut("/knowledge-base/docs/" + docId, body, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "更新文档名称应成功: " + result.getMessage());

        // 验证已改名
        ResponseEntity<Result> getResponse = authGet("/knowledge-base/docs/" + docId, Result.class);
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) getResponse.getBody().getData();
        assertEquals(newDocName, data.get("docName"));

        System.out.println("[10/13] 更新文档名称成功");
    }

    @Test
    @Order(11)
    void shouldEnableAndDisableDocument() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(docId, "需要先上传文档");

        // 禁用文档
        ResponseEntity<Result> disableResponse = authPatch(
                "/knowledge-base/docs/" + docId + "/enable?value=false", Result.class);

        assertEquals(HttpStatus.OK, disableResponse.getStatusCode());
        Result disableResult = disableResponse.getBody();
        assertNotNull(disableResult);
        assertTrue(disableResult.isSuccess(), "禁用文档应成功");

        // 验证已禁用
        @SuppressWarnings("unchecked")
        var disabledData = (Map<String, Object>) authGet(
                "/knowledge-base/docs/" + docId, Result.class).getBody().getData();
        assertEquals(Boolean.FALSE, disabledData.get("enabled"));

        // 重新启用文档
        ResponseEntity<Result> enableResponse = authPatch(
                "/knowledge-base/docs/" + docId + "/enable?value=true", Result.class);

        assertEquals(HttpStatus.OK, enableResponse.getStatusCode());
        Result enableResult = enableResponse.getBody();
        assertNotNull(enableResult);
        assertTrue(enableResult.isSuccess(), "启用文档应成功");

        // 验证已启用
        @SuppressWarnings("unchecked")
        var enabledData = (Map<String, Object>) authGet(
                "/knowledge-base/docs/" + docId, Result.class).getBody().getData();
        assertEquals(Boolean.TRUE, enabledData.get("enabled"));

        System.out.println("[11/13] 文档禁用/启用切换成功");
    }

    @Test
    @Order(12)
    void shouldDownloadDocumentFile() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(docId, "需要先上传文档");

        // 使用 RestTemplate 绕过 TestRestTemplate 的泛型限制，直接读字节
        RestTemplate plainTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<byte[]> response = plainTemplate.exchange(
                url("/knowledge-base/docs/" + docId + "/file"),
                HttpMethod.GET, request, byte[].class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        byte[] body = response.getBody();
        assertNotNull(body, "下载文件内容不应为空");
        assertTrue(body.length > 0, "下载文件大小应大于0");

        String content = new String(body, StandardCharsets.UTF_8);
        assertTrue(content.contains("测试文档"), "文件内容应包含文档标题");
        assertTrue(content.contains("第二段"), "文件内容应包含文档章节");

        System.out.println("[12/13] 下载文档文件成功, size=" + body.length + " bytes");
    }

    @Test
    @Order(13)
    void shouldDeleteDocumentAndKnowledgeBase() {
        assertNotNull(token, "需要先登录获取 token");
        assertNotNull(docId, "需要先上传文档");
        assertNotNull(kbId, "需要先创建知识库");

        // 1. 知识库有文档时不应允许删除
        ResponseEntity<Result> deleteKbWhenHasDocs = authDelete("/knowledge-base/" + kbId, Result.class);

        Result kbResult = deleteKbWhenHasDocs.getBody();
        assertNotNull(kbResult);
        assertFalse(kbResult.isSuccess(), "知识库有文档时不应允许删除");
        assertTrue(kbResult.getMessage().contains("文档"), "错误信息应提示文档存在");

        System.out.println("   └ 知识库有文档时拒绝删除: " + kbResult.getMessage());

        // 2. 删除文档
        ResponseEntity<Result> deleteDocResponse = authDelete(
                "/knowledge-base/docs/" + docId, Result.class);

        assertEquals(HttpStatus.OK, deleteDocResponse.getStatusCode());
        Result deleteDocResult = deleteDocResponse.getBody();
        assertNotNull(deleteDocResult);
        assertTrue(deleteDocResult.isSuccess(), "删除文档应成功");

        System.out.println("   └ 文档删除成功, docId=" + docId);

        // 3. 文档删除后，知识库应可删除
        ResponseEntity<Result> deleteKbResponse = authDelete("/knowledge-base/" + kbId, Result.class);

        assertEquals(HttpStatus.OK, deleteKbResponse.getStatusCode());
        Result deleteKbResult = deleteKbResponse.getBody();
        assertNotNull(deleteKbResult);
        assertTrue(deleteKbResult.isSuccess(), "文档删除后，删除知识库应成功");

        System.out.println("[13/13] 知识库删除成功, kbId=" + kbId);
    }

    // ==================== 边界场景 ====================

    @Test
    @Order(14)
    void shouldRejectDuplicateKnowledgeBaseName() {
        assertNotNull(token, "需要先登录获取 token");

        String dupName = "重复名知识库-" + SUFFIX;
        String body1 = String.format("""
                {
                    "name": "%s",
                    "embeddingModel": "test-model",
                    "collectionName": "dup-test-kb-%s"
                }
                """, dupName, SUFFIX);
        ResponseEntity<Result> createResponse = authPost("/knowledge-base", body1, Result.class);
        assertTrue(createResponse.getBody().isSuccess());
        String dupKbId = (String) createResponse.getBody().getData();

        // 再创建同名
        ResponseEntity<Result> dupResponse = authPost("/knowledge-base", body1, Result.class);
        Result dupResult = dupResponse.getBody();
        assertNotNull(dupResult);
        assertFalse(dupResult.isSuccess(), "重复名称应拒绝创建");
        assertTrue(dupResult.getMessage().contains("已存在"), "错误信息应提示名称重复");

        System.out.println("[14/14] 重复名称拒绝: " + dupResult.getMessage());

        // 清理
        authDelete("/knowledge-base/" + dupKbId, Result.class);
    }

    @Test
    @Order(15)
    void shouldReturnNotFoundForNonExistentKnowledgeBase() {
        assertNotNull(token, "需要先登录获取 token");

        ResponseEntity<Result> response = authGet(
                "/knowledge-base/non-existent-id-99999", Result.class);

        Result result = response.getBody();
        assertNotNull(result);
        assertFalse(result.isSuccess(), "不存在的知识库应返回失败");
        System.out.println("[15/15] 不存在的知识库查询: " + result.getMessage());
    }

    // ==================== 辅助方法 ====================

    private ResponseEntity<Result> post(String path, String body, Class<Result> clazz) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        return restTemplate.exchange(url(path), HttpMethod.POST, request, clazz);
    }

    private ResponseEntity<Result> authPost(String path, String body, Class<Result> clazz) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", token);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        return restTemplate.exchange(url(path), HttpMethod.POST, request, clazz);
    }

    private ResponseEntity<Result> authGet(String path, Class<Result> clazz) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        return restTemplate.exchange(url(path), HttpMethod.GET, request, clazz);
    }

    private ResponseEntity<Result> authPut(String path, String body, Class<Result> clazz) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", token);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        return restTemplate.exchange(url(path), HttpMethod.PUT, request, clazz);
    }

    private ResponseEntity<Result> authPatch(String path, Class<Result> clazz) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        return restTemplate.exchange(url(path), HttpMethod.PATCH, request, clazz);
    }

    private ResponseEntity<Result> authDelete(String path, Class<Result> clazz) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        return restTemplate.exchange(url(path), HttpMethod.DELETE, request, clazz);
    }
}
