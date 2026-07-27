package com.xinshuo.mindflow.framework.mq.producer;

import org.apache.rocketmq.client.producer.SendResult;

import java.util.function.Consumer;

public interface MessageQueueProducer {

    SendResult send(String topic, String keys, String bizDesc, Object body);

    void sendInTransaction(String topic, String keys, String bizDesc, Object body,
                           Consumer<Object> localTransaction);
}
