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
import com.xinshuo.mindflow.core.parser.model.CodeBlock;
import com.xinshuo.mindflow.core.parser.model.HeadingBlock;
import com.xinshuo.mindflow.core.parser.model.ImageBlock;
import com.xinshuo.mindflow.core.parser.model.ListBlock;
import com.xinshuo.mindflow.core.parser.model.ParagraphBlock;
import com.xinshuo.mindflow.core.parser.model.TableBlock;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Block 列表 → 纯文本渲染器
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BlockTextRenderer {

    public static String render(List<Block> blocks) {
        if (blocks == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (b instanceof HeadingBlock h) {
                sb.append("#".repeat(Math.max(1, h.level())))
                        .append(' ').append(h.text() == null ? "" : h.text()).append("\n\n");
            } else if (b instanceof ParagraphBlock p) {
                sb.append(p.text() == null ? "" : p.text()).append("\n\n");
            } else if (b instanceof TableBlock t) {
                if (t.headers() != null) {
                    sb.append(String.join(" | ", t.headers())).append('\n');
                }
                if (t.rows() != null) {
                    for (List<String> row : t.rows()) {
                        sb.append(String.join(" | ", row)).append('\n');
                    }
                }
                sb.append('\n');
            } else if (b instanceof ImageBlock i) {
                if (i.description() != null && !i.description().isBlank()) {
                    sb.append(i.description().strip()).append("\n\n");
                }
                sb.append("![")
                        .append(i.caption() == null ? "" : i.caption()).append("](")
                        .append(i.asset() == null ? "" : i.asset().publicUrl()).append(")\n\n");
            } else if (b instanceof CodeBlock c) {
                sb.append("```").append(c.language() == null ? "" : c.language())
                        .append('\n').append(c.code() == null ? "" : c.code()).append("\n```\n\n");
            } else if (b instanceof ListBlock l) {
                if (l.items() != null) {
                    for (int idx = 0; idx < l.items().size(); idx++) {
                        sb.append(l.ordered() ? (idx + 1) + ". " : "- ")
                                .append(l.items().get(idx)).append('\n');
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString().trim();
    }
}
