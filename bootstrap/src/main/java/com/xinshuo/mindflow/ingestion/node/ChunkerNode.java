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

package com.xinshuo.mindflow.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinshuo.mindflow.core.chunk.ChunkEmbeddingService;
import com.xinshuo.mindflow.core.chunk.ChunkingMode;
import com.xinshuo.mindflow.core.chunk.ChunkingOptions;
import com.xinshuo.mindflow.core.chunk.StructuredChunkingService;
import com.xinshuo.mindflow.core.chunk.VectorChunk;
import com.xinshuo.mindflow.core.parser.model.Block;
import com.xinshuo.mindflow.framework.exception.ClientException;
import com.xinshuo.mindflow.ingestion.domain.context.IngestionContext;
import com.xinshuo.mindflow.ingestion.domain.enums.IngestionNodeType;
import com.xinshuo.mindflow.ingestion.domain.pipeline.NodeConfig;
import com.xinshuo.mindflow.ingestion.domain.result.NodeResult;
import com.xinshuo.mindflow.ingestion.domain.settings.ChunkerSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 文本分块节点
 * <p>
 * blocks 非空走 block-aware，否则用纯文本走 legacy（判断收口在 StructuredChunkingService）；
 * 分块后立即生成 embedding，供后续 IndexerNode 使用。
 */
@Component
@RequiredArgsConstructor
public class ChunkerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final StructuredChunkingService structuredChunkingService;

    @Override
    public String getNodeType() {
        return IngestionNodeType.CHUNKER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        ChunkerSettings settings = parseSettings(config.getSettings());

        List<Block> blocks = context.getDocument() == null ? null : context.getDocument().getBlocks();
        boolean hasBlocks = blocks != null && !blocks.isEmpty();
        String text = StringUtils.hasText(context.getEnhancedText())
                ? context.getEnhancedText()
                : context.getRawText();
        ChunkingOptions options = settings.getStrategy()
                .createDefaultOptions(settings.getChunkSize(), settings.getOverlapSize());

        List<VectorChunk> chunks = structuredChunkingService.chunk(
                blocks, text, settings.getStrategy(), options, settings.getRowsPerChunk());

        if (chunks.isEmpty()) {
            return NodeResult.fail(new ClientException(hasBlocks ? "分块结果为空" : "可分块文本为空"));
        }

        chunkEmbeddingService.embed(chunks, null);

        context.setChunks(chunks);
        return NodeResult.ok("已分块 " + chunks.size() + " 段, path=" + (hasBlocks ? "block-aware" : "legacy-text"));
    }

    private ChunkerSettings parseSettings(JsonNode node) {
        ChunkerSettings settings = objectMapper.convertValue(node, ChunkerSettings.class);
        if (settings == null) {
            settings = ChunkerSettings.builder().build();
        }
        if (settings.getStrategy() == null) {
            settings.setStrategy(ChunkingMode.STRUCTURE_AWARE);
        }
        Integer chunkSize = settings.getChunkSize();
        if (chunkSize == null || chunkSize <= 0) {
            settings.setChunkSize(512);
        }
        if (settings.getOverlapSize() == null || settings.getOverlapSize() < 0) {
            settings.setOverlapSize(128);
        }
        return settings;
    }
}
