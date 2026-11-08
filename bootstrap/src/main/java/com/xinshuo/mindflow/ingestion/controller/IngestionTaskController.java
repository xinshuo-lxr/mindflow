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

package com.xinshuo.mindflow.ingestion.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinshuo.mindflow.framework.convention.Result;
import com.xinshuo.mindflow.framework.web.Results;
import com.xinshuo.mindflow.ingestion.controller.request.IngestionTaskCreateRequest;
import com.xinshuo.mindflow.ingestion.controller.vo.IngestionTaskNodeVO;
import com.xinshuo.mindflow.ingestion.controller.vo.IngestionTaskVO;
import com.xinshuo.mindflow.ingestion.domain.result.IngestionResult;
import com.xinshuo.mindflow.ingestion.service.IngestionTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库采集任务控制层
 */
@RestController
@RequiredArgsConstructor
@Validated
public class IngestionTaskController {

    private final IngestionTaskService taskService;

    @PostMapping("/ingestion/tasks")
    public Result<IngestionResult> create(@RequestBody IngestionTaskCreateRequest request) {
        return Results.success(taskService.execute(request));
    }

    @PostMapping(value = "/ingestion/tasks/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<IngestionResult> upload(@RequestParam("pipelineId") String pipelineId,
                                          @RequestPart("file") MultipartFile file) {
        return Results.success(taskService.upload(pipelineId, file));
    }

    @GetMapping("/ingestion/tasks/{id}")
    public Result<IngestionTaskVO> get(@PathVariable("id") String id) {
        return Results.success(taskService.get(id));
    }

    @GetMapping("/ingestion/tasks/{id}/nodes")
    public Result<List<IngestionTaskNodeVO>> nodes(@PathVariable("id") String id) {
        return Results.success(taskService.listNodes(id));
    }

    @GetMapping("/ingestion/tasks")
    public Result<IPage<IngestionTaskVO>> page(@RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                               @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                               @RequestParam(value = "status", required = false) String status) {
        return Results.success(taskService.page(new Page<>(pageNo, pageSize), status));
    }
}
