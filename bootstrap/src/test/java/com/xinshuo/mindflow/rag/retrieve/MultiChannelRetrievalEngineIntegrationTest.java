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
import com.xinshuo.mindflow.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.xinshuo.mindflow.rag.core.retrieve.RetrievalEngine;
import com.xinshuo.mindflow.rag.core.retrieve.channel.SearchChannel;
import com.xinshuo.mindflow.rag.core.retrieve.channel.SearchChannelType;
import com.xinshuo.mindflow.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.xinshuo.mindflow.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多通道检索引擎装配集成测试（第 17 步）
 *
 * <p>验证检索通道与后置处理器正确装配进引擎，且引擎可正常执行（空结果不抛异常）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
class MultiChannelRetrievalEngineIntegrationTest {

    @Autowired
    private List<SearchChannel> searchChannels;

    @Autowired
    private List<SearchResultPostProcessor> postProcessors;

    @Autowired
    private MultiChannelRetrievalEngine engine;

    @Autowired
    private RetrievalEngine retrievalEngine;

    @Test
    void shouldWireBothChannels() {
        assertFalse(searchChannels.isEmpty(), "应装配至少一个检索通道");
        Set<SearchChannelType> types = searchChannels.stream()
                .map(SearchChannel::getType)
                .collect(Collectors.toSet());
        assertTrue(types.contains(SearchChannelType.INTENT_DIRECTED), "应装配意图定向通道");
        assertTrue(types.contains(SearchChannelType.VECTOR_GLOBAL), "应装配向量全局通道");
        System.out.println("[1] 检索通道装配: " + types);
    }

    @Test
    void shouldWirePostProcessors() {
        assertFalse(postProcessors.isEmpty(), "应装配后置处理器");
        List<String> names = postProcessors.stream().map(SearchResultPostProcessor::getName).toList();
        assertTrue(names.contains("Deduplication"), "应装配去重处理器");
        assertTrue(names.contains("Rerank"), "应装配 Rerank 处理器");
        System.out.println("[2] 后置处理器装配: " + names);
    }

    @Test
    void shouldRunEngineWithEmptyResult() {
        // 空子问题 → 意图定向禁用，全局通道兜底查所有 collection（无数据时返回空，不抛异常）
        List<?> chunks = engine.retrieveKnowledgeChannels(List.of(new SubQuestionIntent("不存在的知识", List.of())), 5);
        assertNotNull(chunks, "检索引擎返回不应为 null");
        System.out.println("[3] 多通道引擎执行完成, chunks=" + chunks.size());
    }

    @Test
    void shouldReturnEmptyRetrievalContextForEmptyIntents() {
        var ctx = retrievalEngine.retrieve(List.of(), 5);
        assertNotNull(ctx, "RetrievalContext 不应为 null");
        assertTrue(ctx.isEmpty(), "空意图应返回空检索上下文");
        assertFalse(ctx.hasKb());
        assertFalse(ctx.hasMcp());
        System.out.println("[4] 空意图 RetrievalContext 正常");
    }
}
