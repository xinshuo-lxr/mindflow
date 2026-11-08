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

package com.xinshuo.mindflow.ingestion.domain.context;

import com.xinshuo.mindflow.core.parser.model.Block;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 结构化文档实体类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StructuredDocument {

    /**
     * 文档纯文本内容
     */
    private String text;

    /**
     * 章节结构（老字段，保留兼容）
     */
    private List<StructuredSection> sections;

    /**
     * 表格结构（老字段，保留兼容）
     */
    private List<StructuredTable> tables;

    /**
     * 文档元数据
     */
    private Map<String, Object> metadata;

    /**
     * 结构化 Block 列表（新链路以此为准）
     */
    private List<Block> blocks;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StructuredSection {

        private String title;

        private Integer level;

        private String content;

        private Integer startOffset;

        private Integer endOffset;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StructuredTable {

        private String title;

        private List<List<String>> rows;

        private Integer startOffset;

        private Integer endOffset;
    }
}
