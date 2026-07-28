package com.xinshuo.mindflow.infra.model;

import com.xinshuo.mindflow.infra.config.AIModelProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 断路器状态转换测试
 *
 * <p>验证三态断路器 CLOSED → OPEN → HALF_OPEN → CLOSED 的完整生命周期，
 * 包括熔断触发、半开探测、成功恢复和再次熔断。</p>
 */
class ModelHealthStoreTest {

    private static final String MODEL_ID = "qwen-plus";

    private ModelHealthStore healthStore;

    @BeforeEach
    void setUp() {
        AIModelProperties properties = new AIModelProperties();
        properties.getSelection().setFailureThreshold(2);   // 失败 2 次熔断
        properties.getSelection().setOpenDurationMs(100L);   // 100ms 后半开（测试用短时间）
        healthStore = new ModelHealthStore(properties);
    }

    // ==================== 初始状态 ====================

    @Test
    void shouldBeAvailableInitially() {
        assertFalse(healthStore.isUnavailable(MODEL_ID));
    }

    @Test
    void shouldAllowCallInitially() {
        assertTrue(healthStore.allowCall(MODEL_ID));
    }

    // ==================== CLOSED → OPEN 熔断 ====================

    @Test
    void shouldOpenAfterConsecutiveFailures() {
        // 失败 1 次：还未达到阈值
        healthStore.markFailure(MODEL_ID);
        assertFalse(healthStore.isUnavailable(MODEL_ID),
                "失败 1 次后仍应可用（阈值 2）");

        // 失败 2 次：触发熔断
        healthStore.markFailure(MODEL_ID);
        assertTrue(healthStore.isUnavailable(MODEL_ID),
                "失败 2 次后应熔断（不可用）");
    }

    @Test
    void shouldNotAllowCallWhenOpen() {
        // 触发熔断
        healthStore.markFailure(MODEL_ID);
        healthStore.markFailure(MODEL_ID);

        assertFalse(healthStore.allowCall(MODEL_ID),
                "熔断状态下不应允许调用");
    }

    // ==================== HALF_OPEN 半开探测 ====================

    @Test
    void shouldTransitionToHalfOpenAfterTimeout() throws InterruptedException {
        // 触发熔断
        healthStore.markFailure(MODEL_ID);
        healthStore.markFailure(MODEL_ID);
        assertTrue(healthStore.isUnavailable(MODEL_ID));

        // 等待超过 openDurationMs
        Thread.sleep(150);

        // 超时后应变为 HALF_OPEN，允许一次试探
        assertTrue(healthStore.allowCall(MODEL_ID),
                "超时后应进入半开状态，允许试探调用");
    }

    @Test
    void shouldOnlyAllowOneProbeInHalfOpen() throws InterruptedException {
        // 触发熔断 → 等待超时
        healthStore.markFailure(MODEL_ID);
        healthStore.markFailure(MODEL_ID);
        Thread.sleep(150);

        // 第一次试探：允许
        assertTrue(healthStore.allowCall(MODEL_ID));

        // 第二次：拒绝（已有 in-flight 的试探）
        assertFalse(healthStore.allowCall(MODEL_ID));

        // isUnavailable 也应返回 true（HALF_OPEN + inFlight）
        assertTrue(healthStore.isUnavailable(MODEL_ID));
    }

    // ==================== HALF_OPEN → CLOSED 成功恢复 ====================

    @Test
    void shouldRecoverAfterProbeSucceeds() throws InterruptedException {
        // 触发熔断 → 等待超时
        healthStore.markFailure(MODEL_ID);
        healthStore.markFailure(MODEL_ID);
        Thread.sleep(150);

        // 试探调用
        assertTrue(healthStore.allowCall(MODEL_ID));

        // 标记成功 → 回到 CLOSED
        healthStore.markSuccess(MODEL_ID);

        assertFalse(healthStore.isUnavailable(MODEL_ID),
                "试探成功后应恢复可用");
        assertTrue(healthStore.allowCall(MODEL_ID),
                "恢复后应允许新调用");
    }

    // ==================== HALF_OPEN → OPEN 再次熔断 ====================

    @Test
    void shouldReOpenIfProbeFails() throws InterruptedException {
        // 触发熔断 → 等待超时
        healthStore.markFailure(MODEL_ID);
        healthStore.markFailure(MODEL_ID);
        Thread.sleep(150);

        // 试探调用
        healthStore.allowCall(MODEL_ID);

        // 试探失败 → 再次熔断
        healthStore.markFailure(MODEL_ID);

        assertTrue(healthStore.isUnavailable(MODEL_ID),
                "试探失败后应再次熔断");
    }

    // ==================== 成功重置失败计数 ====================

    @Test
    void shouldResetFailureCountAfterSuccess() {
        // 失败 1 次
        healthStore.markFailure(MODEL_ID);

        // 标记成功 → 重置计数
        healthStore.markSuccess(MODEL_ID);

        // 再失败 1 次 → 计数从头开始，不应熔断
        healthStore.markFailure(MODEL_ID);
        assertFalse(healthStore.isUnavailable(MODEL_ID));
    }

    // ==================== 边界条件 ====================

    @Test
    void shouldHandleNullId() {
        assertFalse(healthStore.allowCall(null));
        // markSuccess/markFailure 应对 null 静默处理
        assertDoesNotThrow(() -> healthStore.markSuccess(null));
        assertDoesNotThrow(() -> healthStore.markFailure(null));
    }

    @Test
    void shouldHandleMultipleModelsIndependently() throws InterruptedException {
        String model1 = "model-1";
        String model2 = "model-2";

        // model-1 熔断
        healthStore.markFailure(model1);
        healthStore.markFailure(model1);
        assertTrue(healthStore.isUnavailable(model1));

        // model-2 仍可用
        assertFalse(healthStore.isUnavailable(model2));
        assertTrue(healthStore.allowCall(model2));
    }

    @Test
    void shouldBeCallableAfterMarkSuccessOnHealthyModel() {
        // 未熔断的模型 markSuccess 后仍可调用
        healthStore.markSuccess(MODEL_ID);
        assertTrue(healthStore.allowCall(MODEL_ID));
    }
}
