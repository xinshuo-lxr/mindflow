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

package com.xinshuo.mindflow.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.xinshuo.mindflow.knowledge.controller.vo.KnowledgeChunkVO;

import java.util.List;

/**
 * 知识库分片服务接口
 */
public interface KnowledgeChunkService {

    IPage<KnowledgeChunkVO> pageQuery(String docId, KnowledgeChunkPageRequest requestParam);

    KnowledgeChunkVO create(String docId, KnowledgeChunkCreateRequest requestParam);

    void batchCreate(String docId, List<KnowledgeChunkCreateRequest> requestParams);

    void batchCreate(String docId, List<KnowledgeChunkCreateRequest> requestParams, boolean writeVector);

    void update(String docId, String chunkId, KnowledgeChunkUpdateRequest requestParam);

    void delete(String docId, String chunkId);

    void enableChunk(String docId, String chunkId, boolean enabled);

    void batchToggleEnabled(String docId, KnowledgeChunkBatchRequest requestParam, boolean enabled);

    void updateEnabledByDocId(String docId, String kbId, boolean enabled);

    List<KnowledgeChunkVO> listByDocId(String docId);

    void deleteByDocId(String docId);
}
