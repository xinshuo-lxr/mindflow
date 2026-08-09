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

package com.xinshuo.mindflow.rag.core.prompt;

import com.xinshuo.mindflow.framework.convention.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 默认上下文格式化器——把检索到的 chunk 列表拼成 LLM 可读文本
 */
@Service
public class DefaultContextFormatter implements ContextFormatter {

    @Override
    public String formatKbContext(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) sb.append("\n\n---\n\n");
            sb.append("[参考资料").append(i + 1).append("] (相关度: ")
                    .append(String.format("%.2f", chunks.get(i).getScore()))
                    .append(")\n").append(chunks.get(i).getText());
        }
        return sb.toString();
    }
}
