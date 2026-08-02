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

import com.xinshuo.mindflow.rag.controller.vo.ConversationMessageVO;
import com.xinshuo.mindflow.rag.enums.ConversationMessageOrder;
import com.xinshuo.mindflow.rag.service.bo.ConversationMessageBO;
import com.xinshuo.mindflow.rag.service.bo.ConversationSummaryBO;

import java.util.List;

/**
 * 对话消息服务接口
 * <p>
 * 负责消息的持久化和查询。查询时附带每条 assistant 消息的用户反馈状态，
 * 避免前端额外请求。
 */
public interface ConversationMessageService {

    /**
     * 新增对话消息，返回自增/雪花 ID
     */
    String addMessage(ConversationMessageBO conversationMessage);

    /**
     * 获取对话消息列表，支持排序与数量限制。
     * <p>limit 为 null 时返回全部消息。
     */
    List<ConversationMessageVO> listMessages(String conversationId, String userId, Integer limit, ConversationMessageOrder order);

    /**
     * 添加对话摘要
     */
    void addMessageSummary(ConversationSummaryBO conversationSummary);
}
