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

package com.xinshuo.mindflow.ingestion.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinshuo.mindflow.ingestion.controller.request.IngestionTaskCreateRequest;
import com.xinshuo.mindflow.ingestion.controller.vo.IngestionTaskNodeVO;
import com.xinshuo.mindflow.ingestion.controller.vo.IngestionTaskVO;
import com.xinshuo.mindflow.ingestion.domain.result.IngestionResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 数据摄入任务服务
 */
public interface IngestionTaskService {

    IngestionResult execute(IngestionTaskCreateRequest request);

    IngestionResult upload(String pipelineId, MultipartFile file);

    IngestionTaskVO get(String taskId);

    IPage<IngestionTaskVO> page(Page<IngestionTaskVO> page, String status);

    List<IngestionTaskNodeVO> listNodes(String taskId);
}
