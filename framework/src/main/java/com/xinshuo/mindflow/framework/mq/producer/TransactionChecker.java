package com.xinshuo.mindflow.framework.mq.producer;

import com.xinshuo.mindflow.framework.mq.MessageWrapper;

public interface TransactionChecker {

    boolean check(MessageWrapper<?> message);
}
