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

package com.xinshuo.mindflow.rag.core.memory;

import com.xinshuo.mindflow.framework.convention.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 空摘要服务
 */
@Slf4j
@Service
public class NoopConversationMemorySummaryService implements ConversationMemorySummaryService {

    @Override
    public void compressIfNeeded(String conversationId, String userId, ChatMessage message) {

    }

    @Override
    public ChatMessage loadLatestSummary(String conversationId, String userId) {
        return null;
    }

    @Override
    public ChatMessage decorateIfNeeded(ChatMessage summary) {
        return summary;
    }
}
