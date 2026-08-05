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
import cn.hutool.core.util.StrUtil;
import com.xinshuo.mindflow.core.chunk.ChunkingMode;
import com.xinshuo.mindflow.core.chunk.ChunkingOptions;
import com.xinshuo.mindflow.core.chunk.ChunkingStrategy;
import com.xinshuo.mindflow.core.chunk.TextBoundaryOptions;
import com.xinshuo.mindflow.core.chunk.VectorChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class StructureAwareTextChunker implements ChunkingStrategy {

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern CODE_FENCE = Pattern.compile("^```.*$");

    @Override
    public ChunkingMode getType() { return ChunkingMode.STRUCTURE_AWARE; }

    @Override
    public List<VectorChunk> chunk(String text, ChunkingOptions config) {
        if (StrUtil.isBlank(text)) return List.of();
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        TextBoundaryOptions opts = (TextBoundaryOptions) config;
        int target = opts.targetChars(), max = opts.maxChars(), min = opts.minChars();

        List<Span> spans = segment(text);
        if (spans.isEmpty()) {
            return List.of(VectorChunk.builder().content(text).index(0).chunkId(IdUtil.getSnowflakeNextIdStr()).build());
        }
        List<int[]> ranges = pack(spans, text.length(), min, target, max);
        List<VectorChunk> out = new ArrayList<>();
        for (int k = 0; k < ranges.size(); k++) {
            out.add(VectorChunk.builder()
                    .content(text.substring(ranges.get(k)[0], ranges.get(k)[1]))
                    .index(k).chunkId(IdUtil.getSnowflakeNextIdStr()).build());
        }
        return out;
    }

    private record Span(int start, int end) {}

    private List<Span> segment(String text) {
        List<Span> spans = new ArrayList<>();
        int n = text.length(), pos = 0;
        boolean inFence = false;
        int fenceStart = -1, paraStart = -1;
        boolean inPara = false;
        while (pos < n) {
            int le = text.indexOf('\n', pos);
            if (le < 0) le = n;
            int leNl = le < n && text.charAt(le) == '\n' ? le + 1 : le;
            String line = text.substring(pos, le).stripTrailing();
            if (!inFence && CODE_FENCE.matcher(line.strip()).matches()) {
                if (inPara) { spans.add(new Span(paraStart, pos)); inPara = false; }
                inFence = true; fenceStart = pos;
                pos = leNl; continue;
            }
            if (inFence) {
                if (CODE_FENCE.matcher(line.strip()).matches()) {
                    spans.add(new Span(fenceStart, leNl)); inFence = false;
                }
                pos = leNl; continue;
            }
            if (line.isBlank()) {
                if (inPara) { spans.add(new Span(paraStart, pos)); inPara = false; }
                pos = leNl; continue;
            }
            if (HEADING.matcher(line.strip()).matches()) {
                if (inPara) { spans.add(new Span(paraStart, pos)); inPara = false; }
                spans.add(new Span(pos, leNl));
                pos = leNl; continue;
            }
            if (!inPara) { inPara = true; paraStart = pos; }
            pos = leNl;
        }
        if (inFence) spans.add(new Span(fenceStart, n));
        else if (inPara) spans.add(new Span(paraStart, n));
        return spans;
    }

    private List<int[]> pack(List<Span> spans, int textLen, int min, int target, int max) {
        List<int[]> ranges = new ArrayList<>();
        int i = 0;
        while (i < spans.size()) {
            int cs = spans.get(i).start, ce = spans.get(i).end, sz = ce - cs;
            int j = i + 1;
            while (j < spans.size()) {
                int after = spans.get(j).end - cs;
                if (after <= max) { ce = spans.get(j).end; sz = after; j++; }
                else { if (sz < min) { ce = spans.get(j).end; sz = after; j++; } break; }
            }
            ranges.add(new int[]{cs, ce});
            i = j;
        }
        if (ranges.size() >= 2) {
            int[] last = ranges.get(ranges.size() - 1);
            if (last[1] - last[0] < min) {
                int[] prev = ranges.get(ranges.size() - 2);
                if (last[1] - prev[0] <= max * 2) { prev[1] = last[1]; ranges.remove(ranges.size() - 1); }
            }
        }
        return ranges;
    }
}
