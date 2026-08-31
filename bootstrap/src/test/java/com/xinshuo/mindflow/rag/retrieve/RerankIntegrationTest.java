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

package com.xinshuo.mindflow.rag.retrieve;

import com.xinshuo.mindflow.MindflowApplication;
import com.xinshuo.mindflow.framework.convention.RetrievedChunk;
import com.xinshuo.mindflow.framework.exception.RemoteException;
import com.xinshuo.mindflow.infra.rerank.RerankService;
import com.xinshuo.mindflow.rag.core.retrieve.postprocessor.DeduplicationPostProcessor;
import com.xinshuo.mindflow.rag.core.retrieve.postprocessor.RerankPostProcessor;
import com.xinshuo.mindflow.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rerank 集成测试
 *
 * <p>验证处理链装配顺序（Dedup → Rerank），并走真实 RoutingRerankService 调 rerank。
 * 真实模型调用失败时跳过（需要联网 + API Key），不阻塞 CI。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RerankIntegrationTest {

    @Autowired
    private List<SearchResultPostProcessor> postProcessors;

    @Autowired
    private RerankService rerankService;

    @Test
    @Order(1)
    void shouldWireProcessingChainInOrder() {
        assertNotNull(postProcessors, "后处理器链不应为 null");
        assertFalse(postProcessors.isEmpty(), "应装配至少 1 个后处理器");

        List<String> names = postProcessors.stream()
                .sorted((a, b) -> Integer.compare(a.getOrder(), b.getOrder()))
                .map(SearchResultPostProcessor::getName)
                .toList();

        System.out.println("[1] 处理链装配: " + names);

        // 去重（order=1）必须在 Rerank（order=10）之前
        int dedupIdx = names.indexOf("Deduplication");
        int rerankIdx = names.indexOf("Rerank");
        assertTrue(dedupIdx >= 0, "应装配 Deduplication 处理器");
        assertTrue(rerankIdx >= 0, "应装配 Rerank 处理器");
        assertTrue(dedupIdx < rerankIdx, "Deduplication 应先于 Rerank 执行");
    }

    @Test
    @Order(2)
    void shouldHaveDedupFirstRerankLastOrder() {
        // 直接验证 order 数值
        SearchResultPostProcessor dedup = postProcessors.stream()
                .filter(p -> p instanceof DeduplicationPostProcessor)
                .findFirst().orElse(null);
        SearchResultPostProcessor rerank = postProcessors.stream()
                .filter(p -> p instanceof RerankPostProcessor)
                .findFirst().orElse(null);

        assertNotNull(dedup, "应找到 DeduplicationPostProcessor");
        assertNotNull(rerank, "应找到 RerankPostProcessor");
        assertEquals(1, dedup.getOrder(), "Dedup order 应为 1");
        assertEquals(10, rerank.getOrder(), "Rerank order 应为 10");
        System.out.println("[2] Dedup.order=" + dedup.getOrder() + ", Rerank.order=" + rerank.getOrder());
    }

    @Test
    @Order(3)
    void shouldRerankRealCandidates() {
        // 构造 5 条候选，topN=3
        List<RetrievedChunk> candidates = List.of(
                new RetrievedChunk("c1", "入职需要提交身份证复印件和劳动合同", 0.80f),
                new RetrievedChunk("c2", "试用期考核通过后转正", 0.60f),
                new RetrievedChunk("c3", "OA系统的审批流程说明", 0.50f),
                new RetrievedChunk("c4", "保险系统的架构设计文档", 0.40f),
                new RetrievedChunk("c5", "公司 VPN 连接方法", 0.30f)
        );

        List<RetrievedChunk> reranked;
        try {
            reranked = rerankService.rerank("入职需要什么材料", candidates, 3);
        } catch (RemoteException e) {
            // 真实 rerank 模型不可用（未联网 / API 失效 / 模型配额）时跳过
            Assumptions.assumeTrue(false, "真实 Rerank 模型不可用，跳过: " + e.getMessage());
            return;
        }

        assertNotNull(reranked, "rerank 结果不应为 null");
        assertFalse(reranked.isEmpty(), "rerank 结果不应为空");
        assertTrue(reranked.size() <= 3, "rerank 后不应超过 topN=3，实际 " + reranked.size());

        System.out.println("[3] 真实 rerank 返回 " + reranked.size() + " 条");
        reranked.forEach(c -> System.out.println("    - " + c.getId() + " score=" + c.getScore()));
    }
}
