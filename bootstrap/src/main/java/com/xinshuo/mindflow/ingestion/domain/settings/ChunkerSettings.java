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

package com.xinshuo.mindflow.ingestion.domain.settings;

import com.xinshuo.mindflow.core.chunk.ChunkingMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分块器设置
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChunkerSettings {

    private ChunkingMode strategy;

    private Integer chunkSize;

    private Integer overlapSize;

    private String separator;

    /**
     * 表格每个 chunk 的最大数据行数（block-aware 链路硬上限）
     */
    private Integer rowsPerChunk;
}
