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

package com.xinshuo.mindflow.core.chunk.strategy;

import cn.hutool.core.util.IdUtil;
import com.xinshuo.mindflow.core.chunk.ChunkingMode;
import com.xinshuo.mindflow.core.chunk.ChunkingOptions;
import com.xinshuo.mindflow.core.chunk.ChunkingStrategy;
import com.xinshuo.mindflow.core.chunk.FixedSizeOptions;
import com.xinshuo.mindflow.core.chunk.VectorChunk;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class FixedSizeTextChunker implements ChunkingStrategy {

    @Override
    public ChunkingMode getType() { return ChunkingMode.FIXED_SIZE; }

    @Override
    public List<VectorChunk> chunk(String text, ChunkingOptions config) {
        if (!StringUtils.hasText(text)) return List.of();
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        FixedSizeOptions opts = (FixedSizeOptions) config;
        if (opts.chunkSize() == -1) {
            return List.of(VectorChunk.builder().chunkId(IdUtil.getSnowflakeNextIdStr()).index(0).content(normalized).build());
        }
        int size = Math.max(1, opts.chunkSize());
        int overlap = Math.max(0, Math.min(opts.overlapSize(), size - 1));
        int len = normalized.length();
        List<VectorChunk> chunks = new ArrayList<>();
        int idx = 0, start = 0;
        while (start < len) {
            int end = Math.min(start + size, len);
            String content = normalized.substring(start, end);
            if (StringUtils.hasText(content.strip())) {
                chunks.add(VectorChunk.builder().chunkId(IdUtil.getSnowflakeNextIdStr()).index(idx++).content(content).build());
            }
            if (end >= len) break;
            start = Math.max(end, Math.max(0, end - overlap));
        }
        return chunks;
    }
}
