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

package com.xinshuo.mindflow.infra.vlm;

import org.springframework.stereotype.Service;

/**
 * Noop VLM 服务——第 8 步占位，后续替换为真实实现
 */
@Service
public class NoopVlmService implements VlmService {

    @Override
    public String describeImage(byte[] imageBytes, String mime, String prompt, Integer maxOutputTokens) {
        return "待实现：VLM 图生文";
    }
}
