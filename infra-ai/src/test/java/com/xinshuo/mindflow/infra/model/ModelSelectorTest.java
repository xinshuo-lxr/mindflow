package com.xinshuo.mindflow.infra.model;

import com.xinshuo.mindflow.infra.config.AIModelProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型选择器测试
 *
 * <p>验证候选模型筛选、优先级排序、断路器过滤、深度思考过滤等逻辑。</p>
 */
class ModelSelectorTest {

    private AIModelProperties properties;
    private ModelHealthStore healthStore;
    private ModelSelector selector;

    @BeforeEach
    void setUp() {
        properties = new AIModelProperties();
        properties.getSelection().setFailureThreshold(2);
        properties.getSelection().setOpenDurationMs(30000L);

        // 配置两个提供商
        AIModelProperties.ProviderConfig bailian = new AIModelProperties.ProviderConfig();
        bailian.setUrl("https://dashscope.aliyuncs.com");
        bailian.setApiKey("test-key");
        properties.getProviders().put("bailian", bailian);

        AIModelProperties.ProviderConfig ollama = new AIModelProperties.ProviderConfig();
        ollama.setUrl("http://localhost:11434");
        ollama.setApiKey("");
        properties.getProviders().put("ollama", ollama);

        // 配置 chat 候选模型
        AIModelProperties.ModelCandidate c1 = new AIModelProperties.ModelCandidate();
        c1.setId("qwen-plus");
        c1.setProvider("bailian");
        c1.setModel("qwen-plus-latest");
        c1.setPriority(1);

        AIModelProperties.ModelCandidate c2 = new AIModelProperties.ModelCandidate();
        c2.setId("qwen-local");
        c2.setProvider("ollama");
        c2.setModel("qwen3:8b");
        c2.setPriority(2);

        AIModelProperties.ModelCandidate c3 = new AIModelProperties.ModelCandidate();
        c3.setId("qwen3-max");
        c3.setProvider("bailian");
        c3.setModel("qwen3-max");
        c3.setPriority(3);
        c3.setSupportsThinking(true);

        properties.getChat().getCandidates().add(c1);
        properties.getChat().getCandidates().add(c2);
        properties.getChat().getCandidates().add(c3);
        properties.getChat().setDefaultModel("qwen-plus");

        healthStore = new ModelHealthStore(properties);
        selector = new ModelSelector(properties, healthStore);
    }

    // ==================== 基本筛选 ====================

    @Test
    void shouldReturnCandidatesSortedByPriority() {
        List<ModelTarget> candidates = selector.selectChatCandidates(false);

        assertEquals(3, candidates.size());
        assertEquals("qwen-plus", candidates.get(0).id());
        assertEquals("qwen-local", candidates.get(1).id());
        assertEquals("qwen3-max", candidates.get(2).id());
    }

    // ==================== 断路器过滤 ====================

    @Test
    void shouldSkipOpenBreakerModel() {
        // 让 priority 1 的模型熔断
        healthStore.markFailure("qwen-plus");
        healthStore.markFailure("qwen-plus");

        List<ModelTarget> candidates = selector.selectChatCandidates(false);

        assertEquals(2, candidates.size(),
                "熔断的模型应被过滤掉");
        assertEquals("qwen-local", candidates.get(0).id(),
                "priority 2 的模型应递补到第一位");
    }

    @Test
    void shouldReturnEmptyWhenAllOpen() {
        healthStore.markFailure("qwen-plus");
        healthStore.markFailure("qwen-plus");
        healthStore.markFailure("qwen-local");
        healthStore.markFailure("qwen-local");
        healthStore.markFailure("qwen3-max");
        healthStore.markFailure("qwen3-max");

        List<ModelTarget> candidates = selector.selectChatCandidates(false);

        assertTrue(candidates.isEmpty(),
                "所有模型都熔断时应返回空列表");
    }

    // ==================== 深度思考过滤 ====================

    @Test
    void shouldOnlyIncludeThinkingModelsWhenDeepThinking() {
        List<ModelTarget> candidates = selector.selectChatCandidates(true);

        assertEquals(1, candidates.size(),
                "深度思考模式只应保留 supportsThinking=true 的模型");
        assertEquals("qwen3-max", candidates.get(0).id());
    }

    @Test
    void shouldNotFilterWhenNoThinkingRequired() {
        List<ModelTarget> candidates = selector.selectChatCandidates(false);

        assertEquals(3, candidates.size(),
                "非深度思考模式应返回所有候选");
    }

    // ==================== 首选模型排序 ====================

    @Test
    void shouldPutDefaultModelFirst() {
        properties.getChat().setDefaultModel("qwen-local");

        List<ModelTarget> candidates = selector.selectChatCandidates(false);

        assertEquals("qwen-local", candidates.get(0).id(),
                "defaultModel 应排到第一位");
    }

    @Test
    void shouldPutDeepThinkingModelFirst() {
        properties.getChat().setDeepThinkingModel("qwen3-max");

        List<ModelTarget> candidates = selector.selectChatCandidates(true);

        assertEquals("qwen3-max", candidates.get(0).id(),
                "deepThinkingModel 应排到第一位");
    }

    // ==================== 禁用候选 ====================

    @Test
    void shouldSkipDisabledCandidate() {
        properties.getChat().getCandidates().get(0).setEnabled(false);

        List<ModelTarget> candidates = selector.selectChatCandidates(false);

        assertEquals(2, candidates.size());
        assertEquals("qwen-local", candidates.get(0).id(),
                "禁用的模型应被跳过");
    }

    // ==================== Provider 缺失处理 ====================

    @Test
    void shouldSkipCandidateWithMissingProvider() {
        AIModelProperties.ModelCandidate orphan = new AIModelProperties.ModelCandidate();
        orphan.setId("orphan-model");
        orphan.setProvider("unknown-provider");
        orphan.setModel("some-model");
        orphan.setPriority(10);
        properties.getChat().getCandidates().add(orphan);

        List<ModelTarget> candidates = selector.selectChatCandidates(false);

        long orphanCount = candidates.stream()
                .filter(t -> "orphan-model".equals(t.id()))
                .count();
        assertEquals(0, orphanCount,
                "provider 缺失的候选应被跳过");
    }

    // ==================== NOOP Provider 特殊处理 ====================

    @Test
    void shouldAllowNoopProviderWithoutConfig() {
        AIModelProperties.ModelCandidate noop = new AIModelProperties.ModelCandidate();
        noop.setId("rerank-noop");
        noop.setProvider("noop");
        noop.setModel("noop");
        noop.setPriority(100);
        properties.getRerank().getCandidates().add(noop);

        List<ModelTarget> candidates = selector.selectRerankCandidates();

        assertEquals(1, candidates.size());
        assertEquals("rerank-noop", candidates.get(0).id());
        assertNull(candidates.get(0).provider(),
                "NOOP provider 配置可为 null");
    }
}
