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

package com.xinshuo.mindflow.rag.service;

import com.xinshuo.mindflow.rag.controller.request.ConversationUpdateRequest;
import com.xinshuo.mindflow.rag.controller.vo.ConversationVO;
import com.xinshuo.mindflow.rag.service.bo.ConversationCreateBO;

import java.util.List;

/**
 * 会话服务接口
 * <p>
 * 提供会话的列表查询、创建/更新、重命名和删除功能。
 * {@link #createOrUpdate(ConversationCreateBO)} 采用 upsert 语义：
 * conversationId 不存在则创建（含 AI 标题生成），已存在则只更新时间。
 */
public interface ConversationService {

    /**
     * 根据用户 ID 获取会话列表，按最近活动时间倒序
     */
    List<ConversationVO> listByUserId(String userId);

    /**
     * 创建或更新会话——upsert 语义。
     * 首条消息时 conversationId 还不存在，触发创建 + LLM 标题生成；
     * 后续消息时只更新 lastTime。
     */
    void createOrUpdate(ConversationCreateBO request);

    /**
     * 重命名会话，校验标题长度不超过 {@code MemoryProperties.titleMaxLength}
     */
    void rename(String conversationId, ConversationUpdateRequest request);

    /**
     * 删除会话，同时级联删除该会话下的所有消息
     */
    void delete(String conversationId);
}
