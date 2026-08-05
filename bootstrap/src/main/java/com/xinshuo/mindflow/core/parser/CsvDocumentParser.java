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

import com.xinshuo.mindflow.core.parser.model.ParsedDocument;
import com.xinshuo.mindflow.core.parser.model.Provenance;
import com.xinshuo.mindflow.core.parser.model.TableBlock;
import com.xinshuo.mindflow.ingestion.util.MimeTypeDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * CSV 文档解析器
 * 首行为表头，其余为数据行，产出单个 TableBlock
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CsvDocumentParser implements DocumentParser {

    private static final char BOM = '﻿';

    @Override
    public String getParserType() {
        return ParserType.CSV.getType();
    }

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) return false;
        String lower = mimeType.toLowerCase(Locale.ROOT);
        return lower.equals("text/csv") || lower.equals("application/csv") || lower.equals("text/comma-separated-values");
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParsedDocument.of(List.of());
        }
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == BOM) {
            text = text.substring(1);
        }
        List<List<String>> grid = parseCsv(text);
        grid.removeIf(CsvDocumentParser::isBlankRow);
        if (grid.isEmpty()) return ParsedDocument.of(List.of());

        List<String> headers = grid.get(0);
        int width = headers.size();
        List<List<String>> rows = new ArrayList<>(grid.size() - 1);
        for (int i = 1; i < grid.size(); i++) {
            rows.add(padRow(grid.get(i), width));
        }

        Provenance prov = Provenance.ofFile(extractSourceFile(options));
        TableBlock block = new TableBlock(UUID.randomUUID().toString(), prov, List.of(), headers, rows, null);
        return ParsedDocument.of(List.of(block), Map.of(
                "parser", getParserType(), "mimeType", mimeType == null ? "" : mimeType,
                "rows", rows.size(), "columns", width
        ));
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0, len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && text.charAt(i + 1) == '"') { field.append('"'); i += 2; continue; }
                    inQuotes = false; i++; continue;
                }
                field.append(c); i++; continue;
            }
            if (c == '"') { inQuotes = true; i++; }
            else if (c == ',') { current.add(field.toString()); field.setLength(0); i++; }
            else if (c == '\r' || c == '\n') {
                current.add(field.toString()); field.setLength(0);
                rows.add(current); current = new ArrayList<>();
                i += (c == '\r' && i + 1 < len && text.charAt(i + 1) == '\n') ? 2 : 1;
            } else { field.append(c); i++; }
        }
        if (!field.isEmpty() || !current.isEmpty()) { current.add(field.toString()); rows.add(current); }
        return rows;
    }

    private static List<String> padRow(List<String> row, int width) {
        if (row.size() >= width) return row;
        List<String> padded = new ArrayList<>(width);
        padded.addAll(row);
        while (padded.size() < width) padded.add("");
        return padded;
    }

    private static boolean isBlankRow(List<String> row) {
        for (String cell : row) if (cell != null && !cell.isBlank()) return false;
        return true;
    }

    private String extractSourceFile(Map<String, Object> options) {
        if (options == null) return "";
        Object v = options.get("sourceFile");
        return v == null ? "" : v.toString();
    }
}
