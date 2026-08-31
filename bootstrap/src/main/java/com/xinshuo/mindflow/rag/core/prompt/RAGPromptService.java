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

package com.xinshuo.mindflow.rag.core.prompt;

import com.xinshuo.mindflow.framework.convention.RetrievedChunk;
import com.xinshuo.mindflow.rag.core.retrieve.RetrieveRequest;
import com.xinshuo.mindflow.rag.core.retrieve.RetrieverService;
import com.xinshuo.mindflow.rag.core.retrieve.channel.SearchChannelResult;
import com.xinshuo.mindflow.rag.core.retrieve.channel.SearchChannelType;
import com.xinshuo.mindflow.rag.core.retrieve.channel.SearchContext;
import com.xinshuo.mindflow.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * RAG Prompt 编排服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGPromptService {

    private final RetrieverService retrieverService;
    private final ContextFormatter contextFormatter;
    private final List<SearchResultPostProcessor> postProcessors;

    /**
     * 检索并格式化知识库上下文
     *
     * @param query          用户问题
     * @param collectionName 知识库 collection 名称
     * @param topK           返回数量
     * @return 格式化后的上下文字符串，无结果时返回空
     */
    public String buildContext(String query, String collectionName, int topK) {
        List<RetrievedChunk> chunks = retrieverService.retrieve(
                RetrieveRequest.builder().query(query).collectionName(collectionName).topK(topK).build());
        if (chunks.isEmpty()) {
            log.info("RAG 检索无结果, collection={}", collectionName);
            return "";
        }

        // Step 13: 后处理链（去重 → Rerank 精排）
        chunks = applyPostProcessors(chunks, query, topK);

        String context = contextFormatter.formatKbContext(chunks);
        log.info("RAG 检索完成, collection={}, chunks={}, chars={}", collectionName, chunks.size(), context.length());
        return context;
    }

    /**
     * 按 order 顺序执行后处理器链（去重 → Rerank 精排），
     * 构造单通道检索结果作为处理链输入，为 Step 17 多通道铺路
     */
    private List<RetrievedChunk> applyPostProcessors(List<RetrievedChunk> chunks, String query, int topK) {
        SearchContext context = SearchContext.builder()
                .originalQuestion(query)
                .rewrittenQuestion(query)
                .topK(topK)
                .build();

        List<SearchChannelResult> results = List.of(
                SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR_GLOBAL)
                        .chunks(chunks)
                        .build()
        );

        List<SearchResultPostProcessor> chain = postProcessors.stream()
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();

        List<RetrievedChunk> processed = chunks;
        for (SearchResultPostProcessor processor : chain) {
            if (!processor.isEnabled(context)) {
                continue;
            }
            processed = processor.process(processed, results, context);
        }
        return processed;
    }
}
