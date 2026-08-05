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
import com.xinshuo.mindflow.core.parser.model.ParagraphBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ParagraphChunker implements BlockChunker<ParagraphBlock> {

    @Override
    public List<VectorChunk> chunk(ParagraphBlock block, ChunkContext ctx) {
        if (block == null) return List.of();
        String text = block.text() == null ? "" : block.text();
        if (text.isEmpty()) return List.of();

        int maxChars = ctx.config().maxChars();
        int overlap = ctx.config().overlapChars();
        List<String> pieces = splitByChars(text, maxChars, overlap);

        List<VectorChunk> result = new ArrayList<>(pieces.size());
        int chunkIndex = ctx.startIndex();
        for (String piece : pieces) {
            result.add(VectorChunk.builder()
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .index(chunkIndex++)
                    .content(piece)
                    .blockType("PARAGRAPH")
                    .outlinePath(new ArrayList<>(ctx.outlinePath()))
                    .sourceBlockIds(List.of(block.id()))
                    .build());
        }
        return result;
    }

    private static List<String> splitByChars(String text, int maxChars, int overlap) {
        if (text.length() <= maxChars) return List.of(text);
        int step = maxChars - overlap;
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            pieces.add(text.substring(start, end));
            if (end == text.length()) break;
            start += step;
        }
        return pieces;
    }
}
