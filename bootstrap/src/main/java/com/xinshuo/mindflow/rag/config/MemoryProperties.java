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

package com.xinshuo.mindflow.rag.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 记忆配置属性类
 * <p>
 * 用于配置 RAG 系统中对话记忆管理相关参数，包括历史轮数保留、缓存时间、
 * 摘要压缩等功能。可在 application.yaml 中以 {@code rag.memory.xxx} 覆盖。
 *
 * <pre>
 * rag:
 *   memory:
 *     history-keep-turns: 8
 *     summary-enabled: false
 *     summary-start-turns: 9
 *     summary-max-chars: 200
 *     title-max-length: 30
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.memory")
@Validated
public class MemoryProperties {

    /**
     * 保留原文的最近轮数（user+assistant 视为一轮），默认 8。
     * 加载历史时实际取 {@code historyKeepTurns × 2} 条消息。
     */
    @Min(1)
    @Max(100)
    private Integer historyKeepTurns = 8;

    /**
     * 是否启用对话记忆压缩——第 15 步前保持 false
     */
    private Boolean summaryEnabled = false;

    /**
     * 开始摘要的轮数阈值——用户消息数 ≥ 此值后每轮 assistant 回复触发一次压缩检查
     */
    private Integer summaryStartTurns = 9;

    /**
     * 摘要最大字数（字符数），传给 LLM prompt 控制输出长度
     */
    @Min(200)
    @Max(1000)
    private Integer summaryMaxChars = 200;

    /**
     * 会话标题最大长度，同时控制提示词约束和前端截断
     */
    @Min(10)
    @Max(100)
    private Integer titleMaxLength = 30;
}
