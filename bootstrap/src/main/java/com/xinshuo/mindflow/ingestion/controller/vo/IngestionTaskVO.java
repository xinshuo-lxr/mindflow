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

package com.xinshuo.mindflow.ingestion.controller.vo;

import com.xinshuo.mindflow.ingestion.domain.context.NodeLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 知识库摄取任务视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionTaskVO {

    private String id;

    private String pipelineId;

    private String sourceType;

    private String sourceLocation;

    private String sourceFileName;

    private String status;

    private Integer chunkCount;

    private String errorMessage;

    private List<NodeLog> logs;

    private Map<String, Object> metadata;

    private Date startedAt;

    private Date completedAt;

    private String createdBy;

    private Date createTime;

    private Date updateTime;
}
