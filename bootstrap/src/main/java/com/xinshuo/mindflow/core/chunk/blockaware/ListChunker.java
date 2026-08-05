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
import com.xinshuo.mindflow.core.parser.model.ListBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ListChunker implements BlockChunker<ListBlock> {

    @Override
    public List<VectorChunk> chunk(ListBlock block, ChunkContext ctx) {
        if (block == null || block.items() == null || block.items().isEmpty()) return List.of();
        List<String> items = block.items();
        int max = ctx.config().maxListItems();

        if (items.size() <= max) {
            return List.of(buildChunk(items, 1, block, ctx, ctx.startIndex()));
        }

        int per = ctx.config().listItemsPerChunk();
        List<VectorChunk> result = new ArrayList<>();
        int chunkIndex = ctx.startIndex();
        for (int i = 0; i < items.size(); i += per) {
            int end = Math.min(i + per, items.size());
            result.add(buildChunk(items.subList(i, end), i + 1, block, ctx, chunkIndex++));
        }
        return result;
    }

    private VectorChunk buildChunk(List<String> items, int startNumber, ListBlock block,
                                   ChunkContext ctx, int chunkIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append(block.ordered() ? (startNumber + i) + ". " : "- ").append(items.get(i)).append('\n');
        }
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') sb.deleteCharAt(sb.length() - 1);

        return VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(chunkIndex)
                .content(sb.toString())
                .blockType("LIST")
                .outlinePath(new ArrayList<>(ctx.outlinePath()))
                .sourceBlockIds(List.of(block.id()))
                .build();
    }
}
