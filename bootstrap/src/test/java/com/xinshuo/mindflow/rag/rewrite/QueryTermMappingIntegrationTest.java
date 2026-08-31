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

package com.xinshuo.mindflow.rag.rewrite;

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
 * 关键词映射管理集成测试
 *
 * <p>测试链路：登录→创建映射→分页查询→查询详情→更新→删除→删除后查询失败
 *
 * <p>不依赖 LLM，聚焦 /mappings 端点的 CRUD 正确性
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryTermMappingIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static String token;
    private static String mappingId;
    private static String sourceTerm;

    private String url(String path) {
        return "http://localhost:" + port + "/api/mindflow" + path;
    }

    private String suffix() {
        return String.valueOf(System.currentTimeMillis()).substring(7);
    }

    // ============ 前置 ============

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

    // ============ CRUD ============

    @Test
    @Order(2)
    void shouldCreateMapping() {
        String s = suffix();
        sourceTerm = "平安保司" + s;
        String targetTerm = "平安保险公司" + s;
        String body = String.format(
                "{\"sourceTerm\":\"%s\",\"targetTerm\":\"%s\",\"matchType\":1,\"priority\":0,\"enabled\":true,\"remark\":\"测试规则\"}",
                sourceTerm, targetTerm);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/mappings"), HttpMethod.POST, new HttpEntity<>(body, h), Result.class);
        assertTrue(r.getBody().isSuccess(), "创建应成功: " + r.getBody().getMessage());
        mappingId = (String) r.getBody().getData();
        assertNotNull(mappingId, "应返回映射ID");
        System.out.println("[2] 创建映射: id=" + mappingId + ", source=" + sourceTerm);
    }

    @Test
    @Order(3)
    void shouldPageQuery() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/mappings?current=1&size=10"), HttpMethod.GET, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess());
        @SuppressWarnings("unchecked")
        var page = (Map<String, Object>) r.getBody().getData();
        @SuppressWarnings("unchecked")
        var records = (List<Map<String, Object>>) page.get("records");
        assertNotNull(records, "分页记录不应为null");
        assertFalse(records.isEmpty(), "应至少有1条映射规则");
        System.out.println("[3] 分页查询 total=" + page.get("total"));
    }

    @Test
    @Order(4)
    void shouldQueryById() {
        assertNotNull(mappingId, "需要先创建映射");
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/mappings/" + mappingId), HttpMethod.GET, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) r.getBody().getData();
        assertEquals(sourceTerm, data.get("sourceTerm"), "sourceTerm 应一致");
        assertEquals(true, data.get("enabled"), "enabled 应为 true");
        System.out.println("[4] 查询详情: source=" + data.get("sourceTerm") + ", target=" + data.get("targetTerm"));
    }

    @Test
    @Order(5)
    void shouldUpdateMapping() {
        assertNotNull(mappingId, "需要先创建映射");
        String newTarget = "目标公司" + suffix();
        String body = String.format("{\"targetTerm\":\"%s\",\"enabled\":false}", newTarget);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/mappings/" + mappingId), HttpMethod.PUT, new HttpEntity<>(body, h), Result.class);
        assertTrue(r.getBody().isSuccess(), "更新应成功: " + r.getBody().getMessage());

        // 验证更新生效
        ResponseEntity<Result> r2 = restTemplate.exchange(
                url("/mappings/" + mappingId), HttpMethod.GET, new HttpEntity<>(h), Result.class);
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) r2.getBody().getData();
        assertEquals(newTarget, data.get("targetTerm"), "targetTerm 应已更新");
        assertEquals(false, data.get("enabled"), "enabled 应为 false");
        System.out.println("[5] 更新完成: target=" + newTarget);
    }

    @Test
    @Order(6)
    void shouldDeleteMapping() {
        assertNotNull(mappingId, "需要先创建映射");
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/mappings/" + mappingId), HttpMethod.DELETE, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess(), "删除应成功: " + r.getBody().getMessage());
        System.out.println("[6] 删除完成");
    }

    @Test
    @Order(7)
    void shouldFailQueryDeletedMapping() {
        assertNotNull(mappingId, "需要先创建映射");
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/mappings/" + mappingId), HttpMethod.GET, new HttpEntity<>(h), Result.class);
        assertFalse(r.getBody().isSuccess(), "删除后查询应失败");
        System.out.println("[7] 删除后查询失败: " + r.getBody().getMessage());
    }

    @Test
    @Order(8)
    void shouldRejectBlankSourceTerm() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/mappings"), HttpMethod.POST,
                new HttpEntity<>("{\"sourceTerm\":\" \",\"targetTerm\":\"x\"}", h), Result.class);
        assertFalse(r.getBody().isSuccess(), "空白 sourceTerm 应拒绝");
        System.out.println("[8] 空白 sourceTerm 拒绝: " + r.getBody().getMessage());
    }
}
