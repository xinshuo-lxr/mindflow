package com.xinshuo.mindflow.infra.chat;

import com.xinshuo.mindflow.framework.convention.ChatMessage;
import com.xinshuo.mindflow.framework.convention.ChatRequest;
import com.xinshuo.mindflow.framework.exception.RemoteException;
import com.xinshuo.mindflow.infra.config.AIModelProperties;
import com.xinshuo.mindflow.infra.model.ModelHealthStore;
import com.xinshuo.mindflow.infra.model.ModelRoutingExecutor;
import com.xinshuo.mindflow.infra.model.ModelSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RoutingLLMService 集成测试——路由 + fallback
 *
 * <p>测试模型选路、故障切换、流式路由是否正确工作。</p>
 */
@EnableConfigurationProperties(AIModelProperties.class)
@ActiveProfiles("local")
@SpringBootTest(classes = {
        ModelHealthStore.class,
        ModelSelector.class,
        ModelRoutingExecutor.class,
        LlmFirstPacketProbe.class,
        BaiLianChatClient.class,
        OllamaChatClient.class,
        RoutingLLMService.class,
        ChatTestConfig.class
})
class RoutingLLMServiceIT {

    @Autowired
    private AIModelProperties properties;

    @Autowired
    private RoutingLLMService routingService;

    @Autowired
    private ModelHealthStore healthStore;

    @BeforeEach
    void requireApiKey() {
        AIModelProperties.ProviderConfig provider = properties.getProviders().get("bailian");
        assertNotNull(provider, "缺少 bailian 提供商配置");
        assertNotNull(provider.getApiKey(), "缺少 bailian API Key");
        assertFalse(provider.getApiKey().isBlank(), "bailian API Key 为空");
    }

    // ==================== 同步路由 ====================

    @Test
    void shouldRouteAndReturnReply() {
        String reply = routingService.chat("请回复'路由测试通过'");

        assertNotNull(reply);
        assertFalse(reply.isBlank());
        System.out.println("路由同步回复: " + reply);
    }

    @Test
    void shouldRouteWithChatRequest() {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system("你是助手，只用中文回答"),
                        ChatMessage.user("说'你好'")
                ))
                .temperature(0.1)
                .maxTokens(50)
                .build();

        String reply = routingService.chat(request);

        assertNotNull(reply);
        assertFalse(reply.isBlank());
        System.out.println("ChatRequest 路由回复: " + reply);
    }

    // ==================== 流式路由 ====================

    @Test
    void shouldStreamRouteAndReturnContent() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user("用三个词形容杭州")))
                .maxTokens(100)
                .build();

        StringBuilder fullReply = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        routingService.streamChat(request, new StreamCallback() {
            @Override
            public void onContent(String content) {
                System.out.print(content);
                fullReply.append(content);
            }

            @Override
            public void onComplete() {
                System.out.println();
                latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                error.set(e);
                latch.countDown();
            }
        });

        boolean finished = latch.await(60, TimeUnit.SECONDS);
        assertTrue(finished, "应在 60 秒内完成流式响应");
        assertNull(error.get(), "不应有异常: " + error.get());
        assertFalse(fullReply.toString().isBlank());

        System.out.println("流式路由全文: " + fullReply);
    }

    // ==================== 指定 modelId ====================

    @Test
    void shouldRouteBySpecificModelId() {
        String reply = routingService.chat(
                ChatRequest.builder()
                        .messages(List.of(ChatMessage.user("说'你好'")))
                        .maxTokens(50)
                        .build(),
                "qwen-plus"
        );

        assertNotNull(reply);
        assertFalse(reply.isBlank());
        System.out.println("指定 modelId 回复: " + reply);
    }

    @Test
    void shouldThrowForUnknownModelId() {
        assertThrows(RemoteException.class, () ->
                routingService.chat(
                        ChatRequest.builder()
                                .messages(List.of(ChatMessage.user("hi")))
                                .build(),
                        "nonexistent-model"
                )
        );
    }

    // ==================== 简化接口 ====================

    @Test
    void shouldSupportSimplePromptApi() {
        String reply = routingService.chat("1+1等于几？");

        assertNotNull(reply);
        assertFalse(reply.isBlank());
        System.out.println("简化接口回复: " + reply);
    }

    @Test
    void shouldSupportSimpleStreamApi() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> reply = new AtomicReference<>("");

        routingService.streamChat("说'你好世界'", new StreamCallback() {
            @Override
            public void onContent(String content) {
                reply.updateAndGet(s -> s + content);
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                latch.countDown();
            }
        });

        boolean finished = latch.await(30, TimeUnit.SECONDS);
        assertTrue(finished);
        assertFalse(reply.get().isBlank());
        System.out.println("简化流式回复: " + reply.get());
    }
}
