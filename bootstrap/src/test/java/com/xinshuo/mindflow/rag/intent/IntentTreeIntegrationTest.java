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

package com.xinshuo.mindflow.rag.intent;

import com.xinshuo.mindflow.MindflowApplication;
import com.xinshuo.mindflow.framework.convention.Result;
import com.xinshuo.mindflow.rag.core.intent.IntentNode;
import com.xinshuo.mindflow.rag.core.intent.IntentTreeFactory;
import com.xinshuo.mindflow.rag.core.intent.NodeScore;
import com.xinshuo.mindflow.rag.core.intent.NodeScoreFilters;
import com.xinshuo.mindflow.rag.enums.IntentKind;
import com.xinshuo.mindflow.rag.enums.IntentLevel;
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
 * 意图树集成测试
 *
 * <p>测试链路：登录→获取意图树→创建节点→更新节点→批量启用/停用→删除节点→从工厂初始化
 *
 * <p>不依赖 LLM 服务，聚焦意图树 CRUD 和内存模型验证
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntentTreeIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static String token;
    private static String nodeId;

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

    // ============ 意图树查询 ============

    @Test
    @Order(2)
    void shouldGetFullTree() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/intent-tree/trees"), HttpMethod.GET, new HttpEntity<>(h), Result.class);
        assertTrue(r.getBody().isSuccess());
        @SuppressWarnings("unchecked")
        var tree = (List<Map<String, Object>>) r.getBody().getData();
        assertNotNull(tree, "意图树不应为 null");
        System.out.println("[2] 意图树根节点数: " + tree.size());
    }

    // ============ 节点 CRUD ============

    @Test
    @Order(3)
    void shouldCreateNode() {
        String s = suffix();
        String body = String.format(
                "{\"intentCode\":\"test-domain-%s\",\"name\":\"测试领域-%s\",\"level\":0,\"kind\":0,\"description\":\"测试用领域节点\",\"sortOrder\":99,\"enabled\":1}",
                s, s);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/intent-tree"), HttpMethod.POST, new HttpEntity<>(body, h), Result.class);
        assertTrue(r.getBody().isSuccess(), "创建节点应成功: " + r.getBody().getMessage());
        nodeId = (String) r.getBody().getData();
        assertNotNull(nodeId, "应返回节点ID");
        System.out.println("[3] 创建节点: id=" + nodeId);
    }

    @Test
    @Order(4)
    void shouldRejectDuplicateIntentCode() {
        String s = suffix();
        String body = String.format(
                "{\"intentCode\":\"test-dup-%s\",\"name\":\"重复测试-%s\",\"level\":0,\"kind\":0,\"sortOrder\":99,\"enabled\":1}",
                s, s);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", token);
        // 第一次创建应成功
        ResponseEntity<Result> r1 = restTemplate.exchange(
                url("/intent-tree"), HttpMethod.POST, new HttpEntity<>(body, h), Result.class);
        assertTrue(r1.getBody().isSuccess(), "首次创建应成功");
        // 第二次创建相同 intentCode 应失败
        ResponseEntity<Result> r2 = restTemplate.exchange(
                url("/intent-tree"), HttpMethod.POST, new HttpEntity<>(body, h), Result.class);
        assertFalse(r2.getBody().isSuccess(), "重复 intentCode 应被拒绝");
        System.out.println("[4] 重复 intentCode 拒绝: " + r2.getBody().getMessage());
    }

    @Test
    @Order(5)
    void shouldUpdateNode() {
        assertNotNull(nodeId, "需要先创建节点");
        String newName = "已更新-" + suffix();
        String body = String.format("{\"name\":\"%s\",\"description\":\"更新后的描述\"}", newName);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/intent-tree/" + nodeId), HttpMethod.PUT, new HttpEntity<>(body, h), Result.class);
        // PUT 返回 void，检查状态码
        assertTrue(r.getStatusCode().is2xxSuccessful(), "更新应成功");
        System.out.println("[5] 更新节点完成");
    }

    @Test
    @Order(6)
    void shouldBatchEnableAndDisable() {
        assertNotNull(nodeId, "需要先创建节点");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", token);

        // 停用
        String disableBody = String.format("{\"ids\":[\"%s\"]}", nodeId);
        ResponseEntity<Void> r1 = restTemplate.exchange(
                url("/intent-tree/batch/disable"), HttpMethod.POST,
                new HttpEntity<>(disableBody, h), Void.class);
        assertTrue(r1.getStatusCode().is2xxSuccessful(), "停用应成功");

        // 启用
        String enableBody = String.format("{\"ids\":[\"%s\"]}", nodeId);
        ResponseEntity<Void> r2 = restTemplate.exchange(
                url("/intent-tree/batch/enable"), HttpMethod.POST,
                new HttpEntity<>(enableBody, h), Void.class);
        assertTrue(r2.getStatusCode().is2xxSuccessful(), "启用应成功");
        System.out.println("[6] 批量启用/停用 OK");
    }

    @Test
    @Order(7)
    void shouldDeleteNode() {
        assertNotNull(nodeId, "需要先创建节点");
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        ResponseEntity<Void> r = restTemplate.exchange(
                url("/intent-tree/" + nodeId), HttpMethod.DELETE, new HttpEntity<>(h), Void.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), "删除应成功");
        System.out.println("[7] 删除节点完成");
    }

    // ============ 意图树工厂 ============

    @Test
    @Order(8)
    void shouldBuildIntentTreeFromFactory() {
        List<IntentNode> roots = IntentTreeFactory.buildIntentTree();
        assertNotNull(roots, "工厂构建的树不应为 null");
        assertFalse(roots.isEmpty(), "应有至少一个根节点");

        // 验证结构
        boolean hasGroup = roots.stream().anyMatch(n -> "group".equals(n.getId()));
        boolean hasBiz = roots.stream().anyMatch(n -> "biz".equals(n.getId()));
        boolean hasSales = roots.stream().anyMatch(n -> "sales".equals(n.getId()));
        boolean hasSys = roots.stream().anyMatch(n -> "sys".equals(n.getId()));

        assertTrue(hasGroup, "应有集团信息化域");
        assertTrue(hasBiz, "应有业务系统域");
        assertTrue(hasSales, "应有销售数据MCP域");
        assertTrue(hasSys, "应有系统交互域");

        // 验证叶子节点有 fullPath
        List<IntentNode> leafNodes = flattenLeaves(roots);
        assertFalse(leafNodes.isEmpty(), "应有叶子节点");
        for (IntentNode leaf : leafNodes) {
            assertNotNull(leaf.getFullPath(), "叶子节点应有 fullPath: " + leaf.getId());
            assertFalse(leaf.getFullPath().isBlank(), "叶子节点 fullPath 不应为空: " + leaf.getId());
        }

        System.out.println("[8] 工厂构建意图树: " + roots.size() + " 根域, "
                + countAll(roots) + " 总节点, " + leafNodes.size() + " 叶子节点");
    }

    @Test
    @Order(9)
    void shouldVerifyIntentNodeHelpers() {
        // 测试 IntentNode.isLeaf/isKB/isMCP/isSystem
        IntentNode kbLeaf = IntentNode.builder().id("test-kb").name("KB叶子").kind(IntentKind.KB).build();
        IntentNode mcpNode = IntentNode.builder().id("test-mcp").name("MCP节点").kind(IntentKind.MCP)
                .mcpToolId("tool-1").build();
        IntentNode sysNode = IntentNode.builder().id("test-sys").name("系统节点").kind(IntentKind.SYSTEM).build();
        IntentNode parent = IntentNode.builder().id("parent").name("父节点")
                .children(List.of(kbLeaf)).build();

        assertTrue(kbLeaf.isLeaf(), "无子节点应为叶子");
        assertTrue(kbLeaf.isKB(), "kind=KB 应为 KB");
        assertTrue(mcpNode.isMCP(), "kind=MCP 应为 MCP");
        assertTrue(sysNode.isSystem(), "kind=SYSTEM 应为 SYSTEM");
        assertFalse(parent.isLeaf(), "有子节点不应为叶子");

        System.out.println("[9] IntentNode 辅助方法验证通过");
    }

    @Test
    @Order(10)
    void shouldFilterNodeScores() {
        IntentNode kbNode = IntentNode.builder().id("kb-1").name("KB节点").kind(IntentKind.KB).build();
        IntentNode mcpNode = IntentNode.builder().id("mcp-1").name("MCP节点").kind(IntentKind.MCP)
                .mcpToolId("tool-1").build();
        IntentNode mcpNoTool = IntentNode.builder().id("mcp-2").name("MCP无工具").kind(IntentKind.MCP).build();

        List<NodeScore> scores = List.of(
                new NodeScore(kbNode, 0.9),
                new NodeScore(mcpNode, 0.8),
                new NodeScore(mcpNoTool, 0.5)
        );

        List<NodeScore> kbScores = NodeScoreFilters.kb(scores);
        assertEquals(1, kbScores.size(), "应只有1个KB意图");
        assertEquals("kb-1", kbScores.get(0).getNode().getId());

        List<NodeScore> mcpScores = NodeScoreFilters.mcp(scores);
        assertEquals(1, mcpScores.size(), "只有有toolId的MCP才算");
        assertEquals("mcp-1", mcpScores.get(0).getNode().getId());

        List<NodeScore> kbAboveThreshold = NodeScoreFilters.kb(scores, 0.85);
        assertEquals(1, kbAboveThreshold.size(), "KB>=0.85 应为1个");

        System.out.println("[10] NodeScore 过滤器验证通过");
    }

    // ============ 枚举 ============

    @Test
    @Order(11)
    void shouldResolveIntentEnums() {
        assertEquals(IntentKind.KB, IntentKind.fromCode(0));
        assertEquals(IntentKind.SYSTEM, IntentKind.fromCode(1));
        assertEquals(IntentKind.MCP, IntentKind.fromCode(2));
        assertNull(IntentKind.fromCode(null));
        assertNull(IntentKind.fromCode(99));

        assertEquals(IntentLevel.DOMAIN, IntentLevel.fromCode(0));
        assertEquals(IntentLevel.CATEGORY, IntentLevel.fromCode(1));
        assertEquals(IntentLevel.TOPIC, IntentLevel.fromCode(2));
        assertNull(IntentLevel.fromCode(null));
        assertNull(IntentLevel.fromCode(99));

        System.out.println("[11] 枚举 fromCode 验证通过");
    }

    // ============ 辅助方法 ============

    private List<IntentNode> flattenLeaves(List<IntentNode> nodes) {
        List<IntentNode> result = new java.util.ArrayList<>();
        java.util.Deque<IntentNode> stack = new java.util.ArrayDeque<>(nodes);
        while (!stack.isEmpty()) {
            IntentNode n = stack.pop();
            if (n.isLeaf()) {
                result.add(n);
            } else if (n.getChildren() != null) {
                for (IntentNode child : n.getChildren()) {
                    stack.push(child);
                }
            }
        }
        return result;
    }

    private int countAll(List<IntentNode> nodes) {
        int count = 0;
        java.util.Deque<IntentNode> stack = new java.util.ArrayDeque<>(nodes);
        while (!stack.isEmpty()) {
            IntentNode n = stack.pop();
            count++;
            if (n.getChildren() != null) {
                for (IntentNode child : n.getChildren()) {
                    stack.push(child);
                }
            }
        }
        return count;
    }
}
