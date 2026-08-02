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

package com.xinshuo.mindflow.rag.controller;

import com.xinshuo.mindflow.framework.context.UserContext;
import com.xinshuo.mindflow.framework.convention.Result;
import com.xinshuo.mindflow.framework.web.Results;
import com.xinshuo.mindflow.rag.controller.request.ConversationUpdateRequest;
import com.xinshuo.mindflow.rag.controller.vo.ConversationMessageVO;
import com.xinshuo.mindflow.rag.controller.vo.ConversationVO;
import com.xinshuo.mindflow.rag.enums.ConversationMessageOrder;
import com.xinshuo.mindflow.rag.service.ConversationMessageService;
import com.xinshuo.mindflow.rag.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话控制器
 * <p>
 * 提供会话相关的 REST API 接口，包括会话列表获取、重命名、删除
 * 以及会话消息列表获取等功能。用户身份通过 {@link UserContext} 获取。
 */
@RestController
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;

    /**
     * 获取当前用户的会话列表，按最近活动时间倒序
     */
    @GetMapping("/conversations")
    public Result<List<ConversationVO>> listConversations() {
        return Results.success(conversationService.listByUserId(UserContext.getUserId()));
    }

    /**
     * 重命名会话
     */
    @PutMapping("/conversations/{conversationId}")
    public Result<Void> rename(@PathVariable("conversationId") String conversationId,
                               @RequestBody ConversationUpdateRequest request) {
        conversationService.rename(conversationId, request);
        return Results.success();
    }

    /**
     * 删除会话及其所有消息
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> delete(@PathVariable("conversationId") String conversationId) {
        conversationService.delete(conversationId);
        return Results.success();
    }

    /**
     * 获取指定会话的消息列表（时间升序，便于前端从上到下渲染）
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public Result<List<ConversationMessageVO>> listMessages(@PathVariable("conversationId") String conversationId) {
        return Results.success(conversationMessageService.listMessages(
                conversationId, UserContext.getUserId(), null, ConversationMessageOrder.ASC));
    }
}

