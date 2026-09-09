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

import com.xinshuo.mindflow.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.xinshuo.mindflow.rag.config.SearchChannelProperties;
import com.xinshuo.mindflow.rag.core.intent.IntentNode;
import com.xinshuo.mindflow.rag.core.intent.NodeScore;
import com.xinshuo.mindflow.rag.core.retrieve.RetrieverService;
import com.xinshuo.mindflow.rag.core.retrieve.channel.IntentDirectedSearchChannel;
import com.xinshuo.mindflow.rag.core.retrieve.channel.SearchContext;
import com.xinshuo.mindflow.rag.core.retrieve.channel.VectorGlobalSearchChannel;
import com.xinshuo.mindflow.rag.dto.SubQuestionIntent;
import com.xinshuo.mindflow.rag.enums.IntentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 检索通道启用决策单元测试
 * <p>
 * 验证意图定向 / 向量全局两个通道的 isEnabled 阈值逻辑（置信度兜底规则）
 */
class SearchChannelDecisionTest {

    private SearchChannelProperties properties;
    private IntentDirectedSearchChannel intentChannel;
    private VectorGlobalSearchChannel globalChannel;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        properties = new SearchChannelProperties();
        executor = Executors.newSingleThreadExecutor();
        intentChannel = new IntentDirectedSearchChannel(
                mock(RetrieverService.class), properties, executor);
        globalChannel = new VectorGlobalSearchChannel(
                mock(RetrieverService.class), properties, mock(KnowledgeBaseMapper.class), executor);
    }

    private IntentNode kbNode(String id, String collection) {
        return IntentNode.builder().id(id).name(id).collectionName(collection).kind(IntentKind.KB).build();
    }

    private SearchContext contextWith(IntentNode node, double score) {
        return SearchContext.builder()
                .originalQuestion("测试问题")
                .intents(List.of(new SubQuestionIntent("测试问题", List.of(new NodeScore(node, score)))))
                .topK(5)
                .build();
    }

    private SearchContext emptyIntentContext() {
        return SearchContext.builder()
                .originalQuestion("测试问题")
                .intents(List.of())
                .topK(5)
                .build();
    }

    @Test
    void intentChannelShouldEnableWithKbIntent() {
        SearchContext ctx = contextWith(kbNode("biz-oa", "oa-collection"), 0.9);
        assertTrue(intentChannel.isEnabled(ctx), "存在高分 KB 意图时应启用意图定向检索");
    }

    @Test
    void intentChannelShouldDisableWithoutIntent() {
        assertFalse(intentChannel.isEnabled(emptyIntentContext()), "无意图时意图定向通道应禁用");
    }

    @Test
    void intentChannelShouldFilterLowScoreIntent() {
        // minIntentScore 默认 0.4，0.2 分意图被过滤 → 无 KB 意图 → 禁用
        SearchContext ctx = contextWith(kbNode("biz-oa", "oa-collection"), 0.2);
        assertFalse(intentChannel.isEnabled(ctx), "低于 minIntentScore 的意图应被过滤");
    }

    @Test
    void globalChannelShouldEnableWhenNoIntent() {
        assertTrue(globalChannel.isEnabled(emptyIntentContext()), "无意图时全局通道应兜底启用");
    }

    @Test
    void globalChannelShouldDisableWithHighConfidenceIntent() {
        // confidenceThreshold=0.6，单意图 0.9 > 0.6 且 ≥ singleIntentSupplementThreshold=0.8
        SearchContext ctx = contextWith(kbNode("biz-oa", "oa-collection"), 0.9);
        assertFalse(globalChannel.isEnabled(ctx), "高置信度单意图时全局通道不应兜底");
    }

    @Test
    void globalChannelShouldEnableWithLowConfidenceIntent() {
        // 0.3 < confidenceThreshold=0.6 → 兜底
        SearchContext ctx = contextWith(kbNode("biz-oa", "oa-collection"), 0.3);
        assertTrue(globalChannel.isEnabled(ctx), "低置信度意图时全局通道应兜底启用");
    }

    @Test
    void globalChannelShouldEnableWithMediumSingleIntent() {
        // 0.7 ≥ confidenceThreshold(0.6) 但 < singleIntentSupplementThreshold(0.8) → 单意图补充全局检索
        SearchContext ctx = contextWith(kbNode("biz-oa", "oa-collection"), 0.7);
        assertTrue(globalChannel.isEnabled(ctx), "单一中等置信度意图时应补充全局检索");
    }

    @Test
    void channelPrioritiesShouldBeIntentFirstGlobalSecond() {
        assertTrue(intentChannel.getPriority() < globalChannel.getPriority(),
                "意图定向通道优先级应高于全局通道");
        assertTrue(globalChannel.isEnabled(emptyIntentContext()));
    }
}
