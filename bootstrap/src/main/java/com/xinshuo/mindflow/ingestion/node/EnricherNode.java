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
import com.xinshuo.mindflow.core.chunk.VectorChunk;
import com.xinshuo.mindflow.framework.convention.ChatMessage;
import com.xinshuo.mindflow.framework.convention.ChatRequest;
import com.xinshuo.mindflow.ingestion.domain.context.IngestionContext;
import com.xinshuo.mindflow.ingestion.domain.enums.ChunkEnrichType;
import com.xinshuo.mindflow.ingestion.domain.enums.IngestionNodeType;
import com.xinshuo.mindflow.ingestion.domain.pipeline.NodeConfig;
import com.xinshuo.mindflow.ingestion.domain.result.NodeResult;
import com.xinshuo.mindflow.ingestion.domain.settings.EnricherSettings;
import com.xinshuo.mindflow.ingestion.prompt.EnricherPromptManager;
import com.xinshuo.mindflow.ingestion.util.JsonResponseParser;
import com.xinshuo.mindflow.ingestion.util.PromptTemplateRenderer;
import com.xinshuo.mindflow.infra.chat.LLMService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块级富集节点——逐 chunk 提取关键词 / 摘要 / 元数据
 */
@Component
public class EnricherNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final LLMService llmService;

    public EnricherNode(ObjectMapper objectMapper, LLMService llmService) {
        this.objectMapper = objectMapper;
        this.llmService = llmService;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.ENRICHER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<VectorChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.ok("No chunks to enrich");
        }
        EnricherSettings settings = parseSettings(config.getSettings());
        if (settings.getTasks() == null || settings.getTasks().isEmpty()) {
            return NodeResult.ok("No enricher tasks configured");
        }
        boolean attachMetadata = settings.getAttachDocumentMetadata() == null || settings.getAttachDocumentMetadata();
        for (VectorChunk chunk : chunks) {
            if (chunk == null || !StringUtils.hasText(chunk.getContent())) {
                continue;
            }
            if (chunk.getMetadata() == null) {
                chunk.setMetadata(new HashMap<>());
            }
            if (attachMetadata && context.getMetadata() != null) {
                chunk.getMetadata().putAll(context.getMetadata());
            }
            for (EnricherSettings.ChunkEnrichTask task : settings.getTasks()) {
                if (task == null || task.getType() == null) {
                    continue;
                }
                ChunkEnrichType type = task.getType();
                String systemPrompt = StringUtils.hasText(task.getSystemPrompt())
                        ? task.getSystemPrompt()
                        : EnricherPromptManager.systemPrompt(type);
                String userPrompt = buildUserPrompt(task.getUserPromptTemplate(), chunk, context);
                ChatRequest request = ChatRequest.builder()
                        .messages(List.of(
                                ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                                ChatMessage.user(userPrompt)
                        ))
                        .build();
                String response = llmService.chat(request, settings.getModelId());
                applyResult(chunk, type, response);
            }
        }
        return NodeResult.ok("Enricher completed");
    }

    private EnricherSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return EnricherSettings.builder().tasks(List.of()).build();
        }
        EnricherSettings settings = objectMapper.convertValue(node, EnricherSettings.class);
        return settings == null ? EnricherSettings.builder().tasks(List.of()).build() : settings;
    }

    private String buildUserPrompt(String template, VectorChunk chunk, IngestionContext context) {
        String input = chunk.getContent();
        if (!StringUtils.hasText(template)) {
            return input;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("text", input);
        vars.put("content", input);
        vars.put("chunkIndex", chunk.getIndex());
        vars.put("taskId", context.getTaskId());
        vars.put("pipelineId", context.getPipelineId());
        return PromptTemplateRenderer.render(template, vars);
    }

    private void applyResult(VectorChunk chunk, ChunkEnrichType type, String response) {
        switch (type) {
            case KEYWORDS -> chunk.getMetadata().put("keywords", JsonResponseParser.parseStringList(response));
            case SUMMARY ->
                    chunk.getMetadata().put("summary", StringUtils.hasText(response) ? response.trim() : response);
            case METADATA -> chunk.getMetadata().putAll(JsonResponseParser.parseObject(response));
            default -> {
            }
        }
    }
}
