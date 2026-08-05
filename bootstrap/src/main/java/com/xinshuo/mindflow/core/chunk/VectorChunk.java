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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块结果对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorChunk {

    private String chunkId;
    private Integer index;
    private String content;
    private String embeddingText;

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    private float[] embedding;

    @Builder.Default
    private List<com.xinshuo.mindflow.core.parser.model.AssetRef> assets = new ArrayList<>();

    private String blockType;

    @Builder.Default
    private List<String> outlinePath = new ArrayList<>();

    @Builder.Default
    private List<String> sourceBlockIds = new ArrayList<>();

    private String sectionContext;
}
