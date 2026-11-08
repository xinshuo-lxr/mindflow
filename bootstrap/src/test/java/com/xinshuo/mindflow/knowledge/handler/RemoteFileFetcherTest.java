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

package com.xinshuo.mindflow.knowledge.handler;

import com.sun.net.httpserver.HttpServer;
import com.xinshuo.mindflow.MindflowApplication;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 远程文件拉取器测试（第 21 步）
 *
 * <p>用 JDK 内置 HttpServer 模拟远程文件，验证三级变更检测：
 * ETag → Last-Modified（HEAD 短路）→ SHA-256（下载后比较）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RemoteFileFetcherTest {

    @Autowired
    private RemoteFileFetcher remoteFileFetcher;

    private static HttpServer server;
    private static String url;

    private static volatile String body = "# 入职流程\n\n新员工入职需提交身份证复印件。\n";
    private static volatile String etag = "\"v1\"";
    private static volatile String lastModified = "Sun, 08 Nov 2026 10:00:00 GMT";

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/doc.md", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("ETag", etag);
            exchange.getResponseHeaders().add("Last-Modified", lastModified);
            exchange.getResponseHeaders().add("Content-Type", "text/markdown");
            exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"onboarding.md\"");
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Content-Length", String.valueOf(bytes.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/doc.md";
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Order(1)
    void shouldFetchNewFile() throws Exception {
        try (RemoteFileFetcher.RemoteFetchResult result = remoteFileFetcher.fetch(url, "fallback.md")) {
            assertTrue(result.changed(), "首次拉取应视为已变化");
            assertNotNull(result.tempFile(), "应落地临时文件");
            assertEquals(body.getBytes(StandardCharsets.UTF_8).length, result.size(), "字节数与 UTF-8 编码后长度一致");
            assertEquals("onboarding.md", result.fileName(), "应优先取 Content-Disposition 文件名");
            assertNotNull(result.contentHash(), "应计算 SHA-256");
            assertNotNull(result.etag());
            assertEquals(body, Files.readString(result.tempFile(), StandardCharsets.UTF_8));
            System.out.println("[1] 首次拉取 OK, size=" + result.size() + ", hash=" + result.contentHash());
        }
    }

    @Test
    @Order(2)
    void shouldSkipWhenEtagUnchanged() {
        try (RemoteFileFetcher.RemoteFetchResult result =
                     remoteFileFetcher.fetchIfChanged(url, etag, null, null, "fallback.md")) {
            assertFalse(result.changed(), "ETag 相同应判定未变化");
            assertEquals("远程文件未变化", result.message());
            assertNull(result.tempFile(), "未变化不应下载");
            System.out.println("[2] ETag 短路跳过 OK");
        }
    }

    @Test
    @Order(3)
    void shouldSkipWhenContentHashUnchanged() {
        // 服务端去掉 ETag/Last-Modified 的匹配信息（传 null），迫使走 SHA-256 比较
        String hash;
        try (RemoteFileFetcher.RemoteFetchResult first = remoteFileFetcher.fetch(url, "fallback.md")) {
            hash = first.contentHash();
        }
        try (RemoteFileFetcher.RemoteFetchResult result =
                     remoteFileFetcher.fetchIfChanged(url, null, null, hash, "fallback.md")) {
            assertFalse(result.changed(), "内容哈希相同应判定未变化");
            assertEquals("内容哈希未变化", result.message());
            System.out.println("[3] 内容哈希跳过 OK");
        }
    }

    @Test
    @Order(4)
    void shouldDetectChangedContent() {
        String oldHash;
        String oldEtag = etag;
        try (RemoteFileFetcher.RemoteFetchResult first = remoteFileFetcher.fetch(url, "fallback.md")) {
            oldHash = first.contentHash();
        }

        body = "# 入职流程\n\n新增：需提交学历证明。\n";
        etag = "\"v2\"";

        try (RemoteFileFetcher.RemoteFetchResult result =
                     remoteFileFetcher.fetchIfChanged(url, oldEtag, null, oldHash, "fallback.md")) {
            assertTrue(result.changed(), "ETag 与内容都变化时应判定已变化");
            assertNotEquals(oldHash, result.contentHash(), "内容哈希应不同");
            System.out.println("[4] 变更检测 OK, newHash=" + result.contentHash());
        } finally {
            // 恢复，避免影响其它用例
            body = "# 入职流程\n\n新员工入职需提交身份证复印件。\n";
            etag = "\"v1\"";
        }
    }
}
