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

import com.xinshuo.mindflow.ingestion.domain.enums.EnhanceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 增强器设置
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnhancerSettings {

    private String modelId;

    private List<EnhanceTask> tasks;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EnhanceTask {

        private EnhanceType type;

        private String systemPrompt;

        private String userPromptTemplate;
    }
}
