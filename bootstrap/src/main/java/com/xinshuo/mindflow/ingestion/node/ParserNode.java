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
import com.xinshuo.mindflow.core.parser.BlockTextRenderer;
import com.xinshuo.mindflow.core.parser.DocumentParser;
import com.xinshuo.mindflow.core.parser.DocumentParserSelector;
import com.xinshuo.mindflow.core.parser.model.Block;
import com.xinshuo.mindflow.core.parser.model.ParsedDocument;
import com.xinshuo.mindflow.framework.exception.ClientException;
import com.xinshuo.mindflow.ingestion.domain.context.IngestionContext;
import com.xinshuo.mindflow.ingestion.domain.context.StructuredDocument;
import com.xinshuo.mindflow.ingestion.domain.enums.IngestionNodeType;
import com.xinshuo.mindflow.ingestion.domain.pipeline.NodeConfig;
import com.xinshuo.mindflow.ingestion.domain.result.NodeResult;
import com.xinshuo.mindflow.ingestion.domain.settings.ParserSettings;
import com.xinshuo.mindflow.ingestion.util.MimeTypeDetector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档解析节点——按 MIME 路由到 {@link DocumentParser} 并产出结构化 Block
 */
@Component
public class ParserNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final DocumentParserSelector parserSelector;

    public ParserNode(ObjectMapper objectMapper, DocumentParserSelector parserSelector) {
        this.objectMapper = objectMapper;
        this.parserSelector = parserSelector;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.PARSER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        if (context.getRawBytes() == null || context.getRawBytes().length == 0) {
            return NodeResult.fail(new ClientException("解析器缺少原始字节"));
        }

        String mimeType = context.getMimeType();
        if (!StringUtils.hasText(mimeType)) {
            String fileName = context.getSource() == null ? null : context.getSource().getFileName();
            mimeType = MimeTypeDetector.detect(context.getRawBytes(), fileName);
            context.setMimeType(mimeType);
        }

        ParserSettings settings = parseSettings(config.getSettings());
        String fileName = context.getSource() == null ? null : context.getSource().getFileName();

        validateMimeType(settings, mimeType, fileName);
        ParserSettings.ParserRule rule = matchRule(settings, mimeType, fileName);

        DocumentParser parser = parserSelector.selectByMimeType(mimeType);
        if (parser == null) {
            return NodeResult.fail(new ClientException(
                    "未找到 MIME [" + mimeType + "] 对应的解析器,fileName=" + fileName));
        }

        Map<String, Object> ruleOptions = rule == null ? null : rule.getOptions();
        Map<String, Object> options = new HashMap<>(ruleOptions != null ? ruleOptions : Collections.emptyMap());

        if (StringUtils.hasText(fileName) && !options.containsKey("sourceFile")) {
            options.put("sourceFile", fileName);
        }
        if (StringUtils.hasText(context.getTaskId()) && !options.containsKey("documentId")) {
            options.put("documentId", context.getTaskId());
        }

        ParsedDocument parsed = parser.parseStructured(context.getRawBytes(), mimeType, options);
        List<Block> blocks = parsed.blocks() == null ? List.of() : parsed.blocks();
        String renderedText = BlockTextRenderer.render(blocks);
        context.setRawText(renderedText);

        StructuredDocument document = StructuredDocument.builder()
                .text(renderedText)
                .blocks(blocks)
                .metadata(parsed.metadata())
                .build();
        context.setDocument(document);

        return NodeResult.ok(String.format("解析器=%s, blocks=%d, 文本长度=%d",
                parser.getParserType(), blocks.size(), renderedText.length()));
    }

    private void validateMimeType(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            return;
        }
        String resolvedType = resolveType(mimeType, fileName);
        boolean hasMatch = false;
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                hasMatch = true;
                break;
            }
        }
        if (!hasMatch) {
            List<String> allowedTypes = settings.getRules().stream()
                    .filter(rule -> rule != null && StringUtils.hasText(rule.getMimeType()))
                    .map(rule -> normalizeType(rule.getMimeType()))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            throw new ClientException(
                    String.format("文件类型不符合要求。当前文件类型: %s，允许的类型: %s",
                            resolvedType, String.join(", ", allowedTypes)));
        }
    }

    private ParserSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return ParserSettings.builder().rules(List.of()).build();
        }
        return objectMapper.convertValue(node, ParserSettings.class);
    }

    private ParserSettings.ParserRule matchRule(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            return null;
        }
        String resolvedType = resolveType(mimeType, fileName);
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                return rule;
            }
        }
        return null;
    }

    private String resolveType(String mimeType, String fileName) {
        String byName = resolveTypeByName(fileName);
        if (StringUtils.hasText(byName)) {
            return byName;
        }
        if (!StringUtils.hasText(mimeType)) {
            return "UNKNOWN";
        }
        String lower = mimeType.trim().toLowerCase();
        if (lower.contains("pdf")) {
            return "PDF";
        }
        if (lower.contains("markdown")) {
            return "MARKDOWN";
        }
        if (lower.contains("word") || lower.contains("msword") || lower.contains("wordprocessingml")) {
            return "WORD";
        }
        if (lower.contains("excel") || lower.contains("spreadsheetml")) {
            return "EXCEL";
        }
        if (lower.contains("powerpoint") || lower.contains("presentation")) {
            return "PPT";
        }
        if (lower.startsWith("image/")) {
            return "IMAGE";
        }
        if (lower.startsWith("text/")) {
            return "TEXT";
        }
        return "UNKNOWN";
    }

    private String resolveTypeByName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "MARKDOWN";
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "WORD";
        }
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) {
            return "EXCEL";
        }
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "PPT";
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            return "IMAGE";
        }
        if (lower.endsWith(".txt")) {
            return "TEXT";
        }
        return null;
    }

    private String normalizeType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "*", "ALL", "DEFAULT" -> "ALL";
            case "MD", "MARKDOWN" -> "MARKDOWN";
            case "DOC", "DOCX", "WORD" -> "WORD";
            case "XLS", "XLSX", "EXCEL" -> "EXCEL";
            case "PPT", "PPTX", "POWERPOINT" -> "PPT";
            case "TXT", "TEXT" -> "TEXT";
            case "PNG", "JPG", "JPEG", "GIF", "BMP", "WEBP", "IMAGE", "IMG" -> "IMAGE";
            case "PDF" -> "PDF";
            default -> value;
        };
    }
}
