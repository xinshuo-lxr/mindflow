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

package com.xinshuo.mindflow.core.chunk;

import cn.hutool.core.util.IdUtil;
import com.xinshuo.mindflow.core.chunk.blockaware.BlockAwareChunkerDispatcher;
import com.xinshuo.mindflow.core.chunk.blockaware.BlockChunkConfig;
import com.xinshuo.mindflow.core.parser.BlockTextRenderer;
import com.xinshuo.mindflow.core.parser.model.Block;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 结构化分块服务（统一分块入口）
 */
@Service
@RequiredArgsConstructor
public class StructuredChunkingService {

    private static final int DEFAULT_MAX_CHARS = 512;
    private static final int DEFAULT_OVERLAP = 64;
    private static final int DEFAULT_ROWS_PER_CHUNK = 50;
    private static final int DEFAULT_MAX_LIST_ITEMS = 15;
    private static final int DEFAULT_LIST_ITEMS_PER_CHUNK = 10;

    private final BlockAwareChunkerDispatcher blockAwareChunkerDispatcher;
    private final ChunkingStrategyFactory chunkingStrategyFactory;

    public List<VectorChunk> chunk(List<Block> blocks, String fallbackText,
                                   ChunkingMode mode, ChunkingOptions options, Integer rowsPerChunk) {
        if (blocks != null && !blocks.isEmpty()) {
            return blockAwareChunkerDispatcher.dispatch(blocks, toBlockConfig(options, rowsPerChunk));
        }
        if (!StringUtils.hasText(fallbackText)) return List.of();
        return chunkingStrategyFactory.requireStrategy(mode).chunk(fallbackText, options);
    }

    private BlockChunkConfig toBlockConfig(ChunkingOptions opts, Integer rowsPerChunk) {
        Map<String, Integer> cfg = opts == null ? Map.of() : opts.toConfigMap();
        int maxChars = firstPositive(cfg);
        int overlap = firstNonNegative(cfg);
        if (overlap >= maxChars) overlap = Math.max(0, maxChars - 1);
        int rows = (rowsPerChunk != null && rowsPerChunk > 0) ? rowsPerChunk : DEFAULT_ROWS_PER_CHUNK;
        return new BlockChunkConfig(maxChars, overlap, rows, DEFAULT_MAX_LIST_ITEMS, DEFAULT_LIST_ITEMS_PER_CHUNK);
    }

    private static int firstPositive(Map<String, Integer> cfg) {
        for (String key : new String[]{"chunkSize", "targetChars", "maxChars"}) {
            Integer v = cfg.get(key);
            if (v != null && v > 0) return v;
        }
        return DEFAULT_MAX_CHARS;
    }

    private static int firstNonNegative(Map<String, Integer> cfg) {
        for (String key : new String[]{"overlapSize", "overlapChars"}) {
            Integer v = cfg.get(key);
            if (v != null && v >= 0) return v;
        }
        return DEFAULT_OVERLAP;
    }
}
