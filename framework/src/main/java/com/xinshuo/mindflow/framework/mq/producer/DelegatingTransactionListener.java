package com.xinshuo.mindflow.framework.mq.producer;

import com.xinshuo.mindflow.framework.mq.MessageWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

@Slf4j
@RocketMQTransactionListener
public class DelegatingTransactionListener implements RocketMQLocalTransactionListener {

    static final String HEADER_TX_ID = "TRANSACTION_CONTEXT_ID";
    static final String HEADER_TOPIC = "TRANSACTION_TOPIC";

    private final ConcurrentMap<String, Consumer<Object>> localTransactionMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TransactionChecker> checkerMap = new ConcurrentHashMap<>();

    @Autowired
    private PlatformTransactionManager transactionManager;

    public void registerLocalTransaction(String txId, Consumer<Object> localTransaction) {
        localTransactionMap.put(txId, localTransaction);
    }

    public void registerChecker(String topic, TransactionChecker checker) {
        checkerMap.put(topic, checker);
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        String txId = (String) message.getHeaders().get(HEADER_TX_ID);
        Consumer<Object> localTransaction = txId != null ? localTransactionMap.remove(txId) : null;
        if (localTransaction == null) {
            log.error("[事务消息] 未找到本地事务逻辑, txId={}", txId);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> localTransaction.accept(arg));
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("[事务消息] 本地事务执行失败, txId={}", txId, e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        String topic = (String) message.getHeaders().get(HEADER_TOPIC);
        TransactionChecker checker = topic != null ? checkerMap.get(topic) : null;
        if (checker == null) {
            log.warn("[事务消息] 回查时未找到 topic={} 对应的 checker, 默认 ROLLBACK", topic);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            MessageWrapper<?> wrapper = (MessageWrapper<?>) message.getPayload();
            boolean committed = checker.check(wrapper);
            RocketMQLocalTransactionState state = committed
                    ? RocketMQLocalTransactionState.COMMIT
                    : RocketMQLocalTransactionState.ROLLBACK;
            log.info("[事务消息] 回查结果: topic={}, state={}", topic, state);
            return state;
        } catch (Exception e) {
            log.error("[事务消息] 回查异常, topic={}", topic, e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }
}
