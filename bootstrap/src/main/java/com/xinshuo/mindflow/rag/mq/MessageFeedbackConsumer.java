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

package com.xinshuo.mindflow.rag.mq;

import com.xinshuo.mindflow.framework.mq.MessageWrapper;
import com.xinshuo.mindflow.rag.mq.event.MessageFeedbackEvent;
import com.xinshuo.mindflow.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 消息反馈 MQ 消费者，负责将点赞/点踩事件异步持久化到数据库
 *
 * <p>{@code rocketmq.consumer.enabled=false} 时不注册消费者——本地/测试环境没有 MQ Broker 时
 * 避免启动失败；生产环境默认注册（与 ragent 行为一致）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rocketmq.consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "message-feedback_topic${unique-name:}",
        consumerGroup = "message-feedback_cg${unique-name:}"
)
public class MessageFeedbackConsumer implements RocketMQListener<MessageWrapper<MessageFeedbackEvent>> {

    private final MessageFeedbackService feedbackService;

    @Override
    public void onMessage(MessageWrapper<MessageFeedbackEvent> message) {
        MessageFeedbackEvent event = message.getBody();

        log.info("[消费者] 开始处理点赞/点踩事件，messageId: {}, userId: {}, vote: {}, keys: {}",
                event.getMessageId(), event.getUserId(), event.getVote(), message.getKeys());
        feedbackService.submitFeedbackByEvent(event);
    }
}
