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
import com.xinshuo.mindflow.rag.controller.request.QueryTermMappingCreateRequest;
import com.xinshuo.mindflow.rag.core.rewrite.QueryRewriteService;
import com.xinshuo.mindflow.rag.core.rewrite.QueryTermMappingService;
import com.xinshuo.mindflow.rag.core.rewrite.RewriteResult;
import com.xinshuo.mindflow.rag.service.QueryTermMappingAdminService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 查询改写 + 多问句拆分集成测试
 *
 * <p>术语归一化走真实 DB + Redis（确定性断言）；
 * LLM 改写走真实模型（弱断言：非空 / 非空白），失败时服务内置兜底逻辑。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryRewriteIntegrationTest {

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private QueryTermMappingService queryTermMappingService;

    @Autowired
    private QueryTermMappingAdminService queryTermMappingAdminService;

    private String suffix() {
        return String.valueOf(System.currentTimeMillis()).substring(7);
    }

    // ============ 术语归一化（确定性） ============

    @Test
    @Order(1)
    void shouldNormalizeWithMapping() {
        String s = suffix();
        String source = "平安保司" + s;
        String target = "平安保险公司" + s;

        QueryTermMappingCreateRequest req = new QueryTermMappingCreateRequest();
        req.setSourceTerm(source);
        req.setTargetTerm(target);
        req.setMatchType(1);
        req.setPriority(0);
        req.setEnabled(true);
        queryTermMappingAdminService.create(req);

        String normalized = queryTermMappingService.normalize(source + " 理赔流程");
        assertTrue(normalized.contains(target), "归一化后应包含目标词: " + normalized);
        assertFalse(normalized.contains(source), "归一化后不应残留源词: " + normalized);

        System.out.println("[1] 归一化: '" + source + " 理赔流程' → '" + normalized + "'");
    }

    @Test
    @Order(2)
    void shouldKeepTextWhenNoMapping() {
        String raw = "OA系统审批流程" + suffix();
        assertEquals(raw, queryTermMappingService.normalize(raw),
                "无匹配规则时应原样返回");
        System.out.println("[2] 无规则原样返回 OK");
    }

    // ============ 查询改写（真实 LLM，弱断言） ============

    @Test
    @Order(3)
    void shouldRewriteQuestion() {
        String rewritten = queryRewriteService.rewrite("请帮我介绍一下OA系统的功能");
        assertNotNull(rewritten, "改写结果不应为null");
        assertFalse(rewritten.isBlank(), "改写结果不应为空白（失败时兜底为归一化原问题）");
        System.out.println("[3] rewrite: '请帮我介绍一下OA系统的功能' → '" + rewritten + "'");
    }

    @Test
    @Order(4)
    void shouldRewriteAndSplitMultiQuestion() {
        RewriteResult result = queryRewriteService.rewriteWithSplit("OA系统怎么用？请假流程是什么？");
        assertNotNull(result, "RewriteResult 不应为null");
        assertNotNull(result.rewrittenQuestion(), "改写主查询不应为null");
        assertNotNull(result.subQuestions(), "子问题列表不应为null");
        assertFalse(result.subQuestions().isEmpty(), "子问题列表不应为空");
        System.out.println("[4] rewriteWithSplit: rewrite='" + result.rewrittenQuestion()
                + "', subQuestions=" + result.subQuestions());
    }

    @Test
    @Order(5)
    void shouldRewriteGreeting() {
        RewriteResult result = queryRewriteService.rewriteWithSplit("你好");
        assertNotNull(result);
        assertFalse(result.rewrittenQuestion().isBlank(), "问候语改写不应为空");
        System.out.println("[5] 问候语改写: '" + result.rewrittenQuestion() + "'");
    }
}
