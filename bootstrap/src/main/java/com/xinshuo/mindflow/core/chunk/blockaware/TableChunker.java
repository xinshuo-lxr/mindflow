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

package com.xinshuo.mindflow.core.chunk.blockaware;

import cn.hutool.core.util.IdUtil;
import com.xinshuo.mindflow.core.chunk.VectorChunk;
import com.xinshuo.mindflow.core.parser.model.TableBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TableChunker implements BlockChunker<TableBlock> {

    @Override
    public List<VectorChunk> chunk(TableBlock block, ChunkContext ctx) {
        if (block == null) return List.of();
        List<String> headers = block.headers() == null ? List.of() : block.headers();
        List<List<String>> rows = block.rows() == null ? List.of() : block.rows();
        if (headers.isEmpty() && rows.isEmpty()) return List.of();

        int maxChars = Math.max(1, ctx.config().maxChars());
        int maxRows = Math.max(1, ctx.config().rowsPerChunk());
        String sectionContext = buildSectionContext(block);
        List<VectorChunk> result = new ArrayList<>();
        int chunkIndex = ctx.startIndex();

        if (rows.isEmpty()) {
            result.add(buildChunk(headers, List.of(), block, ctx, chunkIndex, sectionContext));
            return result;
        }

        List<List<String>> group = new ArrayList<>();
        int groupCost = 0;
        for (List<String> row : rows) {
            int rowCost = renderKeyValueRow(headers, row).length();
            if (group.size() >= maxRows || (!group.isEmpty() && groupCost + rowCost > maxChars)) {
                result.add(buildChunk(headers, group, block, ctx, chunkIndex++, sectionContext));
                group = new ArrayList<>();
                groupCost = 0;
            }
            group.add(row);
            groupCost += rowCost;
        }
        result.add(buildChunk(headers, group, block, ctx, chunkIndex, sectionContext));
        return result;
    }

    private VectorChunk buildChunk(List<String> headers, List<List<String>> rows,
                                   TableBlock block, ChunkContext ctx, int chunkIndex, String sectionContext) {
        String markdown = renderMarkdownTable(headers, rows);
        String embeddingText = buildEmbeddingText(headers, rows, sectionContext);
        return VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(chunkIndex)
                .content(markdown)
                .embeddingText(embeddingText)
                .blockType("TABLE")
                .outlinePath(new ArrayList<>(ctx.outlinePath()))
                .sourceBlockIds(List.of(block.id()))
                .sectionContext(sectionContext)
                .build();
    }

    private String buildEmbeddingText(List<String> headers, List<List<String>> rows, String sectionContext) {
        String kv = renderKeyValueRows(headers, rows);
        if (sectionContext == null || sectionContext.isEmpty()) return kv;
        return kv.isEmpty() ? sectionContext : sectionContext + "\n" + kv;
    }

    private String renderKeyValueRows(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : rows) {
            String line = renderKeyValueRow(headers, row);
            if (line.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    private String renderKeyValueRow(List<String> headers, List<String> row) {
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < row.size(); c++) {
            String value = row.get(c);
            if (value == null || value.isEmpty()) continue;
            String key = c < headers.size() ? headers.get(c) : "";
            if (!line.isEmpty()) line.append("; ");
            if (!key.isEmpty()) line.append(key.replaceAll("\\r\\n|\\r|\\n", " ")).append(": ");
            line.append(value.replaceAll("\\r\\n|\\r|\\n", " "));
        }
        return line.toString();
    }

    private String renderMarkdownTable(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers);
        sb.append("|").append("---|".repeat(Math.max(0, headers.size()))).append('\n');
        for (List<String> row : rows) appendRow(sb, row);
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, List<String> cells) {
        sb.append('|');
        for (String cell : cells) {
            String v = cell == null ? "" : cell.replace("|", "\\|").replaceAll("\\r\\n|\\r|\\n", "<br>");
            sb.append(' ').append(v).append(" |");
        }
        sb.append('\n');
    }

    private String buildSectionContext(TableBlock block) {
        StringBuilder ctx = new StringBuilder();
        if (block.provenance() != null && block.provenance().sheetName() != null)
            ctx.append("sheet=").append(block.provenance().sheetName());
        if (block.captionText() != null && !block.captionText().isEmpty()) {
            if (!ctx.isEmpty()) ctx.append("; ");
            ctx.append("caption=").append(block.captionText());
        }
        if (block.headers() != null && !block.headers().isEmpty()) {
            if (!ctx.isEmpty()) ctx.append("; ");
            ctx.append("headers=").append(String.join(", ", block.headers()));
        }
        return ctx.isEmpty() ? null : ctx.toString();
    }
}
