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
import com.xinshuo.mindflow.core.parser.model.AssetRef;
import com.xinshuo.mindflow.core.parser.model.ImageBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ImageChunker implements BlockChunker<ImageBlock> {

    @Override
    public List<VectorChunk> chunk(ImageBlock block, ChunkContext ctx) {
        if (block == null || block.asset() == null) return List.of();
        AssetRef asset = block.asset();
        String visible = pickCaption(block);
        String markdown = "![" + visible + "](" + asset.publicUrl() + ")";

        String description = block.description();
        boolean hasDescription = description != null && !description.isBlank();
        String content = hasDescription ? description.strip() + "\n\n" + markdown : markdown;
        String embeddingText = hasDescription ? description.strip() : null;

        return List.of(VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(ctx.startIndex())
                .content(content)
                .embeddingText(embeddingText)
                .blockType("IMAGE")
                .outlinePath(new ArrayList<>(ctx.outlinePath()))
                .sourceBlockIds(List.of(block.id()))
                .assets(List.of(asset))
                .sectionContext(buildSectionContext(block))
                .build());
    }

    private String pickCaption(ImageBlock block) {
        if (block.caption() != null && !block.caption().isEmpty()) return block.caption();
        if (block.altText() != null && !block.altText().isEmpty()) return block.altText();
        return "";
    }

    private String buildSectionContext(ImageBlock block) {
        if (block.provenance() == null || block.provenance().sheetName() == null) return null;
        return "sheet=" + block.provenance().sheetName();
    }
}
