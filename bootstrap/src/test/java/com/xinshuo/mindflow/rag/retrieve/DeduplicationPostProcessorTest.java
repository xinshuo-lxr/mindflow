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
import com.xinshuo.mindflow.rag.core.retrieve.channel.SearchChannelResult;
import com.xinshuo.mindflow.rag.core.retrieve.channel.SearchChannelType;
import com.xinshuo.mindflow.rag.core.retrieve.channel.SearchContext;
import com.xinshuo.mindflow.rag.core.retrieve.postprocessor.DeduplicationPostProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 去重后置处理器单元测试（纯逻辑，无 Spring）
 */
class DeduplicationPostProcessorTest {

    private final DeduplicationPostProcessor processor = new DeduplicationPostProcessor();

    private final SearchContext context = SearchContext.builder()
            .originalQuestion("测试问题")
            .topK(5)
            .build();

    private RetrievedChunk chunk(String id, String text, double score) {
        return new RetrievedChunk(id, text, (float) score);
    }

    @Test
    void shouldDeduplicateById() {
        List<SearchChannelResult> results = List.of(
                SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR_GLOBAL)
                        .chunks(List.of(
                                chunk("c1", "内容一", 0.8),
                                chunk("c1", "内容一重复", 0.7),  // 同 id，分数更低
                                chunk("c2", "内容二", 0.6)
                        ))
                        .build()
        );

        List<RetrievedChunk> dedup = processor.process(List.of(), results, context);
        assertEquals(2, dedup.size(), "相同 id 应去重");
        assertTrue(dedup.stream().anyMatch(c -> "c2".equals(c.getId())), "应保留 c2");
    }

    @Test
    void shouldKeepHighestScoreForDuplicate() {
        List<SearchChannelResult> results = List.of(
                SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR_GLOBAL)
                        .chunks(List.of(
                                chunk("c1", "低分", 0.5),
                                chunk("c1", "高分", 0.9)
                        ))
                        .build()
        );

        List<RetrievedChunk> dedup = processor.process(List.of(), results, context);
        assertEquals(1, dedup.size());
        assertEquals(0.9, dedup.get(0).getScore().doubleValue(), 0.0001, "应保留最高分");
    }

    @Test
    void shouldPreferHigherPriorityChannel() {
        // 同一 chunk 出现在意图检索（优先级1）和全局检索（优先级3）
        List<SearchChannelResult> results = List.of(
                SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR_GLOBAL)
                        .chunks(List.of(chunk("c1", "全局版", 0.6)))
                        .build(),
                SearchChannelResult.builder()
                        .channelType(SearchChannelType.INTENT_DIRECTED)
                        .chunks(List.of(chunk("c1", "意图版", 0.8)))
                        .build()
        );

        List<RetrievedChunk> dedup = processor.process(List.of(), results, context);
        assertEquals(1, dedup.size());
        assertEquals(0.8, dedup.get(0).getScore().doubleValue(), 0.0001, "意图通道优先，应保留更高分");
    }

    @Test
    void shouldFallbackToTextHashWhenIdNull() {
        List<SearchChannelResult> results = List.of(
                SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR_GLOBAL)
                        .chunks(List.of(
                                chunk(null, "相同内容", 0.5),
                                chunk(null, "相同内容", 0.9)  // 无 id，靠内容哈希去重
                        ))
                        .build()
        );

        List<RetrievedChunk> dedup = processor.process(List.of(), results, context);
        assertEquals(1, dedup.size(), "无 id 时应按内容哈希去重");
        assertEquals(0.9, dedup.get(0).getScore().doubleValue(), 0.0001);
    }
}
