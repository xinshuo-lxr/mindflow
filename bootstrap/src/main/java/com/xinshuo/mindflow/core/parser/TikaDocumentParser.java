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

package com.xinshuo.mindflow.core.parser;

import com.xinshuo.mindflow.core.parser.model.Block;
import com.xinshuo.mindflow.core.parser.model.ParagraphBlock;
import com.xinshuo.mindflow.core.parser.model.ParsedDocument;
import com.xinshuo.mindflow.core.parser.model.Provenance;
import com.xinshuo.mindflow.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Apache Tika 文档解析器
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TikaDocumentParser implements DocumentParser {

    private static final Tika TIKA = new Tika();

    @Override
    public String getParserType() {
        return ParserType.TIKA.getType();
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParsedDocument.of(List.of());
        }

        String text;
        try (ByteArrayInputStream is = new ByteArrayInputStream(content)) {
            text = TIKA.parseToString(is);
            text = TextCleanupUtil.cleanup(text);
        } catch (Exception e) {
            log.error("Tika 结构化解析失败，MIME 类型: {}", mimeType, e);
            throw new ServiceException("文档解析失败: " + e.getMessage());
        }

        Provenance prov = Provenance.ofFile(extractSourceFile(options));
        List<Block> blocks = new ArrayList<>();
        for (String segment : text.split("\\n{2,}")) {
            String trimmed = segment.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            blocks.add(new ParagraphBlock(UUID.randomUUID().toString(), prov, List.of(), trimmed));
        }
        return ParsedDocument.of(blocks, Map.of("parser", getParserType(), "mimeType", mimeType == null ? "" : mimeType));
    }

    private String extractSourceFile(Map<String, Object> options) {
        if (options == null) return "";
        Object v = options.get("sourceFile");
        return v == null ? "" : v.toString();
    }

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) return false;
        String lower = mimeType.toLowerCase(Locale.ROOT);
        if (lower.startsWith("text/markdown") || lower.startsWith("text/x-markdown")) return false;
        if (lower.equals("text/csv") || lower.equals("application/csv") || lower.equals("text/comma-separated-values"))
            return false;
        if (lower.startsWith("text/")) return true;
        return lower.equals("application/json") || lower.equals("application/xml")
                || lower.equals("application/xhtml+xml") || lower.equals("application/rtf");
    }
}
