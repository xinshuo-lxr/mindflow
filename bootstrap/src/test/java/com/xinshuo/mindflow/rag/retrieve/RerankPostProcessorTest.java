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

import com.xinshuo.mindflow.framework.convention.RetrievedChunk;
import com.xinshuo.mindflow.infra.rerank.RerankService;
import com.xinshuo.mindflow.rag.config.RAGConfigProperties;
import com.xinshuo.mindflow.rag.core.retrieve.channel.SearchChannelResult;
import com.xinshuo.mindflow.rag.core.retrieve.channel.SearchChannelType;
import com.xinshuo.mindflow.rag.core.retrieve.channel.SearchContext;
import com.xinshuo.mindflow.rag.core.retrieve.postprocessor.RerankPostProcessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rerank 后置处理器单元测试（纯逻辑，fake RerankService）
 */
class RerankPostProcessorTest {

    private final SearchContext context = SearchContext.builder()
            .originalQuestion("入职需要什么材料")
            .rewrittenQuestion("入职需要的材料清单")
            .topK(3)
            .build();

    private final List<SearchChannelResult> results = List.of(
            SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .chunks(List.of(
                            chunk("c1", "内容一", 0.8),
                            chunk("c2", "内容二", 0.6),
                            chunk("c3", "内容三", 0.5),
                            chunk("c4", "内容四", 0.4),
                            chunk("c5", "内容五", 0.3)
                    ))
                    .build()
    );

    private RetrievedChunk chunk(String id, String text, double score) {
        return new RetrievedChunk(id, text, (float) score);
    }

    @Test
    void shouldRerankAndTrimToTopN() {
        RAGConfigProperties config = new RAGConfigProperties();
        config.setRerankEnabled(true);

        RerankService fake = (query, candidates, topN) -> {
            // 模拟 rerank：倒序 + 截断 topN
            List<RetrievedChunk> reversed = new ArrayList<>(candidates);
            reversed.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
            return reversed.subList(0, Math.min(topN, reversed.size()));
        };

        RerankPostProcessor processor = new RerankPostProcessor(fake, config);
        List<RetrievedChunk> out = processor.process(results.get(0).getChunks(), results, context);

        assertNotNull(out);
        assertEquals(3, out.size(), "rerank 后应只剩 topN=3 条");
        assertEquals("c1", out.get(0).getId(), "最高分应排第一");
    }

    @Test
    void shouldSkipRerankWhenDisabled() {
        RAGConfigProperties config = new RAGConfigProperties();
        config.setRerankEnabled(false);

        RerankService fake = (query, candidates, topN) -> {
            throw new AssertionError("rerank 不应被调用");
        };

        RerankPostProcessor processor = new RerankPostProcessor(fake, config);
        assertFalse(processor.isEnabled(context), "rerank 关闭时处理器应禁用");
    }

    @Test
    void shouldReturnEmptyForEmptyChunks() {
        RAGConfigProperties config = new RAGConfigProperties();
        config.setRerankEnabled(true);

        RerankService fake = (query, candidates, topN) -> {
            throw new AssertionError("空列表不应触发 rerank");
        };

        RerankPostProcessor processor = new RerankPostProcessor(fake, config);
        List<RetrievedChunk> out = processor.process(List.of(), results, context);
        assertTrue(out.isEmpty(), "空列表应原样返回");
    }

    @Test
    void shouldFallbackToOriginalWhenRerankFails() {
        RAGConfigProperties config = new RAGConfigProperties();
        config.setRerankEnabled(true);

        RerankService broken = (query, candidates, topN) -> {
            throw new IllegalStateException("rerank 服务不可用");
        };

        RerankPostProcessor processor = new RerankPostProcessor(broken, config);
        List<RetrievedChunk> out = processor.process(results.get(0).getChunks(), results, context);

        assertEquals(5, out.size(), "rerank 失败应降级返回原始结果，不阻断检索");
        assertNotNull(out.get(0).getId());
    }

    @Test
    void shouldUseMainQuestionForRerank() {
        RAGConfigProperties config = new RAGConfigProperties();
        config.setRerankEnabled(true);

        final String[] seenQuery = {null};
        RerankService fake = (query, candidates, topN) -> {
            seenQuery[0] = query;
            return candidates;
        };

        RerankPostProcessor processor = new RerankPostProcessor(fake, config);
        processor.process(results.get(0).getChunks(), results, context);

        assertEquals("入职需要的材料清单", seenQuery[0], "应使用主问题（优先改写后的问题）");
    }
}
