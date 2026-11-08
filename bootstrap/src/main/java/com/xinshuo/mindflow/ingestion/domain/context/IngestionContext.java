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

import com.xinshuo.mindflow.core.chunk.VectorChunk;
import com.xinshuo.mindflow.core.parser.model.AssetRef;
import com.xinshuo.mindflow.ingestion.domain.enums.IngestionStatus;
import com.xinshuo.mindflow.rag.core.vector.VectorSpaceId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档摄取上下文
 * <p>
 * 在流水线执行过程中承载并传递所有中间数据与状态。所有节点共享同一实例，就地写入。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionContext {

    /**
     * 摄取任务唯一标识
     */
    private String taskId;

    /**
     * 执行本次摄取的管道ID
     */
    private String pipelineId;

    /**
     * 文档源
     */
    private DocumentSource source;

    /**
     * 原始字节
     */
    private byte[] rawBytes;

    /**
     * MIME 类型
     */
    private String mimeType;

    /**
     * 解析后的原始文本
     */
    private String rawText;

    /**
     * 结构化文档
     */
    private StructuredDocument document;

    /**
     * 分块结果
     */
    private List<VectorChunk> chunks;

    /**
     * 增强后的文本
     */
    private String enhancedText;

    /**
     * 关键词
     */
    private List<String> keywords;

    /**
     * 生成的问题
     */
    private List<String> questions;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;

    /**
     * 目标向量空间（为空时用默认集合）
     */
    private VectorSpaceId vectorSpaceId;

    /**
     * 当前任务状态
     */
    private IngestionStatus status;

    /**
     * 节点日志
     */
    private List<NodeLog> logs;

    /**
     * 失败异常
     */
    private Throwable error;

    /**
     * 是否跳过 IndexerNode 的向量写入
     * <p>
     * 为 true 时 IndexerNode 仅做校验与 chunkId/embedding 填充，
     * 由调用方在同一事务内统一写向量。
     */
    @Builder.Default
    private boolean skipIndexerWrite = false;

    /**
     * 文档级资产引用
     */
    @Builder.Default
    private List<AssetRef> assets = new ArrayList<>();
}
