package com.xinshuo.mindflow.infra.chat;

import com.xinshuo.mindflow.framework.convention.ChatMessage;
import com.xinshuo.mindflow.framework.convention.ChatRequest;
import com.xinshuo.mindflow.infra.config.AIModelProperties;
import com.xinshuo.mindflow.infra.model.ModelTarget;
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
 * 百炼 ChatClient 集成测试——真实 HTTP 调用
 *
 * <p>使用 {@code @ActiveProfiles("local")} 从 classpath 加载
 * {@code application-local.yaml} 中的真实 API Key。</p>
 */
@EnableConfigurationProperties(AIModelProperties.class)
@ActiveProfiles("local")
@SpringBootTest(classes = {
        BaiLianChatClient.class,
        ChatTestConfig.class
})
class BaiLianChatClientIT {

    @Autowired
    private AIModelProperties properties;

    @Autowired
    private BaiLianChatClient client;

    private ModelTarget target;

    @BeforeEach
    void setUp() {
        AIModelProperties.ProviderConfig provider = properties.getProviders().get("bailian");
        assertNotNull(provider, "缺少 bailian 提供商配置，请检查 application-local.yaml");
        assertNotNull(provider.getApiKey(), "缺少 bailian API Key，请检查 application-local.yaml");
        assertFalse(provider.getApiKey().isBlank(), "bailian API Key 为空，请检查 application-local.yaml");

        AIModelProperties.ModelCandidate candidate = properties.getChat().getCandidates().get(0);
        target = new ModelTarget(candidate.getId(), candidate, provider);
    }

    // ==================== 同步调用 ====================

    @Test
    void shouldReturnNonEmptyReply() {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user("请回复'测试通过'，不要回复其他内容")))
                .temperature(0.1)
                .maxTokens(50)
                .build();

        String reply = client.chat(request, target);

        assertNotNull(reply);
        assertFalse(reply.isBlank(), "回复不应为空");
        System.out.println("同步回复: " + reply);
    }

    @Test
    void shouldHandleMultiTurnConversation() {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system("你是一个助手，回答问题要简洁"),
                        ChatMessage.user("我的名字是张三"),
                        ChatMessage.assistant("你好张三，有什么可以帮你的？"),
                        ChatMessage.user("我叫什么名字？")
                ))
                .temperature(0.1)
                .maxTokens(50)
                .build();

        String reply = client.chat(request, target);

        assertNotNull(reply);
        assertTrue(reply.contains("张三"),
                "多轮对话应记住上下文中的名字，实际回复: " + reply);
        System.out.println("多轮回复: " + reply);
    }

    // ==================== 流式调用 ====================

    @Test
    void shouldStreamChunks() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user("用一句话介绍杭州")))
                .maxTokens(200)
                .build();

        StringBuilder fullReply = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        StreamCancellationHandle handle = client.streamChat(request, new StreamCallback() {
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
        }, target);

        boolean finished = latch.await(60, TimeUnit.SECONDS);
        assertTrue(finished, "应在 60 秒内完成流式响应");
        assertNull(error.get(), "不应有异常: " + error.get());
        assertFalse(fullReply.toString().isBlank(), "流式回复不应为空");

        System.out.println("流式全文: " + fullReply);
    }

    @Test
    void shouldReceiveMultipleChunks() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user("请用三句话介绍北京")))
                .maxTokens(300)
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Integer> chunkCount = new AtomicReference<>(0);

        client.streamChat(request, new StreamCallback() {
            @Override
            public void onContent(String content) {
                chunkCount.updateAndGet(c -> c + 1);
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                latch.countDown();
            }
        }, target);

        latch.await(60, TimeUnit.SECONDS);
        assertTrue(chunkCount.get() > 1,
                "三句话的介绍应该产生多个 chunk，实际: " + chunkCount.get());
        System.out.println("收到 " + chunkCount.get() + " 个 chunk");
    }

    @Test
    void shouldAllowCancellation() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user("写一篇关于人工智能的500字文章")))
                .maxTokens(1000)
                .build();

        StringBuilder received = new StringBuilder();
        CountDownLatch firstChunkLatch = new CountDownLatch(1);

        StreamCancellationHandle handle = client.streamChat(request, new StreamCallback() {
            @Override
            public void onContent(String content) {
                received.append(content);
                firstChunkLatch.countDown();      // 收到第一个 chunk 后发信号
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(Throwable e) {
            }
        }, target);

        // 等到第一个 chunk 到达
        firstChunkLatch.await(30, TimeUnit.SECONDS);
        assertFalse(received.toString().isEmpty(), "至少应该收到第一个 chunk");

        // 取消
        handle.cancel();
        Thread.sleep(500);  // 给取消一点时间生效

        System.out.println("取消前收到: " + received);
        // 取消成功不抛异常即可
    }
}
