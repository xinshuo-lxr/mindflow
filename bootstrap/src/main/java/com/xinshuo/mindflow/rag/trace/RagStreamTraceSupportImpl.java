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

package com.xinshuo.mindflow.rag.trace;

import com.xinshuo.mindflow.framework.trace.RagStreamTraceSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 跨线程 stream trace 空实现
 *
 * <p>当前 trace 功能未启用，所有 span 操作均为 no-op，
 * 保证 AbstractOpenAIStyleChatClient 的 {@code @Autowired RagStreamTraceSupport} 正常注入。
 */
@Slf4j
@Component
public class RagStreamTraceSupportImpl implements RagStreamTraceSupport {

    @Override
    public StreamSpan beginStreamNode(String name, String type) {
        return NOOP_SPAN;
    }
}
