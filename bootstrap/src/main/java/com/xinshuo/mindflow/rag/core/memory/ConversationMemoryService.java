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

import java.util.List;

/**
 * 对话记忆服务接口——RAGChatService 直接调用此接口，不碰具体存储
 */
public interface ConversationMemoryService {

    List<ChatMessage> load(String conversationId, String userId);

    String append(String conversationId, String userId, ChatMessage message);

    default List<ChatMessage> loadAndAppend(String conversationId, String userId, ChatMessage message) {
        List<ChatMessage> history = load(conversationId, userId);
        append(conversationId, userId, message);
        return history;
    }
}
