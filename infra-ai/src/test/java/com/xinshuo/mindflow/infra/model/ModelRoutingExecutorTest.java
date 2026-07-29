package com.xinshuo.mindflow.infra.model;

import com.xinshuo.mindflow.framework.exception.RemoteException;
import com.xinshuo.mindflow.infra.config.AIModelProperties;
import com.xinshuo.mindflow.infra.enums.ModelCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型路由执行器测试——fallback 链逻辑
 *
 * <p>验证：多候选依次尝试、失败自动切换、断路器过滤、全部失败抛异常。</p>
 */
class ModelRoutingExecutorTest {

    private static final ModelCapability CAP = ModelCapability.CHAT;

    private ModelHealthStore healthStore;
    private ModelRoutingExecutor executor;

    // 模拟两个"客户端"对象
    private static final String CLIENT_A = "clientA";
    private static final String CLIENT_B = "clientB";

    // 两个 ModelTarget
    private ModelTarget targetA;
    private ModelTarget targetB;

    @BeforeEach
    void setUp() {
        AIModelProperties properties = new AIModelProperties();
        properties.getSelection().setFailureThreshold(2);
        properties.getSelection().setOpenDurationMs(30000L);
        healthStore = new ModelHealthStore(properties);
        executor = new ModelRoutingExecutor(healthStore);

        AIModelProperties.ModelCandidate ca = new AIModelProperties.ModelCandidate();
        ca.setId("model-a");
        ca.setProvider("bailian");
        ca.setModel("qwen-plus");
        ca.setPriority(1);

        AIModelProperties.ModelCandidate cb = new AIModelProperties.ModelCandidate();
        cb.setId("model-b");
        cb.setProvider("ollama");
        cb.setModel("qwen3");
        cb.setPriority(2);

        AIModelProperties.ProviderConfig pa = new AIModelProperties.ProviderConfig();
        pa.setUrl("https://a.example.com");

        AIModelProperties.ProviderConfig pb = new AIModelProperties.ProviderConfig();
        pb.setUrl("https://b.example.com");

        targetA = new ModelTarget(ca.getId(), ca, pa);
        targetB = new ModelTarget(cb.getId(), cb, pb);
    }

    // ==================== 成功路径 ====================

    @Test
    void shouldReturnFromFirstCandidate() {
        Function<ModelTarget, Object> resolver = t -> t == targetA ? CLIENT_A : CLIENT_B;
        ModelCaller<Object, String> caller = (client, target) -> {
            assertEquals(CLIENT_A, client);
            return "success from A";
        };

        String result = executor.executeWithFallback(CAP, List.of(targetA, targetB), resolver, caller);

        assertEquals("success from A", result);
        assertFalse(healthStore.isUnavailable("model-a"), "成功后不应熔断");
    }

    // ==================== Fallback 链 ====================

    @Test
    void shouldFallbackWhenFirstFails() {
        Function<ModelTarget, Object> resolver = t -> t == targetA ? CLIENT_A : CLIENT_B;
        ModelCaller<Object, String> caller = (client, target) -> {
            if (client == CLIENT_A) {
                throw new RuntimeException("A 挂了");
            }
            return "success from B";
        };

        String result = executor.executeWithFallback(CAP, List.of(targetA, targetB), resolver, caller);

        assertEquals("success from B", result);
    }

    @Test
    void shouldMarkFailureOnFailedModel() {
        Function<ModelTarget, Object> resolver = t -> t == targetA ? CLIENT_A : CLIENT_B;
        ModelCaller<Object, String> caller = (client, target) -> {
            if (client == CLIENT_A) {
                throw new RuntimeException("A 挂了");
            }
            return "success from B";
        };

        executor.executeWithFallback(CAP, List.of(targetA, targetB), resolver, caller);

        // A 失败了一次，还未达到阈值 2，仍可用
        assertFalse(healthStore.isUnavailable("model-a"));
        // B 成功了，健康
        assertFalse(healthStore.isUnavailable("model-b"));
    }

    // ==================== 断路器跳过 ====================

    @Test
    void shouldSkipHealthStoreDeniedModel() {
        // 让 model-a 熔断
        healthStore.markFailure("model-a");
        healthStore.markFailure("model-a");
        assertTrue(healthStore.isUnavailable("model-a"));

        Function<ModelTarget, Object> resolver = t -> t == targetA ? CLIENT_A : CLIENT_B;
        ModelCaller<Object, String> caller = (client, target) -> {
            // executor 会先 resolve 再检查 allowCall，
            // 熔断的 model-a 不会被实际调用
            assertEquals(CLIENT_B, client, "熔断模型应该被跳过，实际调用的应该是 B");
            return "success from B";
        };

        String result = executor.executeWithFallback(CAP, List.of(targetA, targetB), resolver, caller);

        assertEquals("success from B", result);
    }

    @Test
    void shouldSkipNullClient() {
        // targetA 的 resolver 返回 null
        Function<ModelTarget, Object> resolver = t -> t == targetA ? null : CLIENT_B;
        ModelCaller<Object, String> caller = (client, target) -> "success from B";

        String result = executor.executeWithFallback(CAP, List.of(targetA, targetB), resolver, caller);

        assertEquals("success from B", result);
    }

    // ==================== 全部失败 ====================

    @Test
    void shouldThrowWhenAllCandidatesFail() {
        Function<ModelTarget, Object> resolver = t -> t == targetA ? CLIENT_A : CLIENT_B;
        ModelCaller<Object, String> caller = (client, target) -> {
            throw new RuntimeException(client + " 挂了");
        };

        RemoteException ex = assertThrows(RemoteException.class, () ->
                executor.executeWithFallback(CAP, List.of(targetA, targetB), resolver, caller)
        );

        assertTrue(ex.getMessage().contains("All"));
        assertTrue(ex.getMessage().contains("Chat"));
    }

    @Test
    void shouldThrowWhenTargetsEmpty() {
        Function<ModelTarget, Object> resolver = t -> "unused";
        ModelCaller<Object, String> caller = (client, target) -> "unused";

        RemoteException ex = assertThrows(RemoteException.class, () ->
                executor.executeWithFallback(CAP, List.of(), resolver, caller)
        );

        assertTrue(ex.getMessage().contains("No"));
    }

    // ==================== 健康状态恢复 ====================

    @Test
    void shouldResetHealthAfterSuccess() {
        // 先失败一次
        Function<ModelTarget, Object> resolver = t -> CLIENT_A;
        ModelCaller<Object, String> failingCaller = (client, target) -> {
            throw new RuntimeException("fail");
        };

        assertThrows(RemoteException.class, () ->
                executor.executeWithFallback(CAP, List.of(targetA), resolver, failingCaller)
        );

        // 验证 A 被标记失败
        assertFalse(healthStore.isUnavailable("model-a"), "失败1次未达阈值，仍可用");

        // 再成功一次
        ModelCaller<Object, String> successCaller = (client, target) -> "ok";
        executor.executeWithFallback(CAP, List.of(targetA), resolver, successCaller);

        // A 应该恢复健康
        assertFalse(healthStore.isUnavailable("model-a"));
    }
}
