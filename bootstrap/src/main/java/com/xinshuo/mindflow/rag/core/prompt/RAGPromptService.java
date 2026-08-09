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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
        String context = contextFormatter.formatKbContext(chunks);
        log.info("RAG 检索完成, collection={}, chunks={}, chars={}", collectionName, chunks.size(), context.length());
        return context;
    }
}
