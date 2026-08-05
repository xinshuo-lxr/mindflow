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

package com.xinshuo.mindflow.knowledge;

import com.xinshuo.mindflow.MindflowApplication;
import com.xinshuo.mindflow.core.chunk.ChunkingMode;
import com.xinshuo.mindflow.core.chunk.StructuredChunkingService;
import com.xinshuo.mindflow.core.chunk.VectorChunk;
import com.xinshuo.mindflow.core.chunk.strategy.FixedSizeTextChunker;
import com.xinshuo.mindflow.core.chunk.strategy.StructureAwareTextChunker;
import com.xinshuo.mindflow.core.parser.DocumentParser;
import com.xinshuo.mindflow.core.parser.DocumentParserSelector;
import com.xinshuo.mindflow.core.parser.MarkdownDocumentParser;
import com.xinshuo.mindflow.core.parser.TikaDocumentParser;
import com.xinshuo.mindflow.core.parser.model.Block;
import com.xinshuo.mindflow.core.parser.model.CodeBlock;
import com.xinshuo.mindflow.core.parser.model.HeadingBlock;
import com.xinshuo.mindflow.core.parser.model.ListBlock;
import com.xinshuo.mindflow.core.parser.model.ParagraphBlock;
import com.xinshuo.mindflow.core.parser.model.ParsedDocument;
import com.xinshuo.mindflow.core.parser.model.TableBlock;
import com.xinshuo.mindflow.framework.convention.Result;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文档分块管道集成测试
 *
 * <p>测试链路：上传 MD → startChunk → 验证解析+分块结果
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentChunkIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DocumentParserSelector parserSelector;

    @Autowired
    private StructuredChunkingService structuredChunkingService;

    @Autowired
    private FixedSizeTextChunker fixedSizeTextChunker;

    @Autowired
    private StructureAwareTextChunker structureAwareTextChunker;

    private static String token;
    private static String kbId;
    private static String docId;

    private String url(String path) {
        return "http://localhost:" + port + "/api/mindflow" + path;
    }

    // ============= 基础：登录 + 创建KB + 上传 =============

    @Test
    @Order(1)
    void shouldLoginAndCreateKb() {
        // 登录
        String loginBody = "{\"username\":\"admin\",\"password\":\"admin\"}";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/auth/login"), HttpMethod.POST, new HttpEntity<>(loginBody, h), Result.class);
        assertTrue(r.getBody().isSuccess());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) r.getBody().getData();
        token = (String) data.get("token");
        assertNotNull(token);

        // 建 KB
        String suffix = String.valueOf(System.currentTimeMillis()).substring(7);
        h.set("Authorization", token);
        String kbBody = String.format(
                "{\"name\":\"分块测试KB-%s\",\"embeddingModel\":\"test-model\",\"collectionName\":\"chunk-test-%s\"}", suffix, suffix);
        r = restTemplate.exchange(url("/knowledge-base"), HttpMethod.POST,
                new HttpEntity<>(kbBody, h), Result.class);
        assertTrue(r.getBody().isSuccess());
        kbId = (String) r.getBody().getData();
        assertNotNull(kbId);
        System.out.println("[1/5] KB 创建成功, kbId=" + kbId);
    }

    @Test
    @Order(2)
    void shouldUploadMarkdownFile() {
        assertNotNull(token);
        assertNotNull(kbId);

        String mdContent = """
                # 系统架构设计

                本系统采用微服务架构，主要包含以下模块：

                ## 用户服务

                用户服务负责认证、鉴权和用户信息管理。

                ## 订单服务

                订单服务处理订单的创建、支付和状态流转。

                ```java
                public class OrderService {
                    public Order create(OrderDTO dto) { return new Order(); }
                }
                ```

                ## 数据表

                | 字段 | 类型 | 说明 |
                |------|------|------|
                | id | bigint | 主键 |
                | name | varchar | 名称 |

                - 高可用
                - 可扩展
                - 易维护
                """;

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(mdContent.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "architecture.md"; }
        });
        body.add("sourceType", "file");
        body.add("processMode", "chunk");
        body.add("chunkStrategy", "structure_aware");

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.set("Authorization", token);
        HttpEntity<MultiValueMap<String, Object>> req = new HttpEntity<>(body, h);

        ResponseEntity<Result> r = restTemplate.exchange(
                url("/knowledge-base/" + kbId + "/docs/upload"),
                HttpMethod.POST, req, Result.class);

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(r.getBody().isSuccess());
        @SuppressWarnings("unchecked")
        var d = (Map<String, Object>) r.getBody().getData();
        docId = (String) d.get("id");
        assertEquals("pending", d.get("status"));
        assertEquals("markdown", d.get("fileType"));
        System.out.println("[2/5] MD 上传成功, docId=" + docId);
    }

    // ============= 解析器单元测试 =============

    @Test
    @Order(3)
    void shouldParseMarkdownToBlocks() {
        String md = """
                # 标题一

                第一段内容。

                第二段内容。

                ```java
                System.out.println("hello");
                ```

                - 项目一
                - 项目二

                ## 标题二

                | 列A | 列B |
                |-----|-----|
                | v1  | v2  |
                """;

        DocumentParser parser = parserSelector.select("Markdown");
        assertNotNull(parser, "应有 Markdown 解析器");
        assertTrue(parser instanceof MarkdownDocumentParser);

        ParsedDocument doc = parser.parseStructured(
                md.getBytes(StandardCharsets.UTF_8), "text/markdown", Map.of());

        List<Block> blocks = doc.blocks();
        assertFalse(blocks.isEmpty(), "Block 列表不应为空");

        // 类型统计
        long headingCount = blocks.stream().filter(b -> b instanceof HeadingBlock).count();
        long paragraphCount = blocks.stream().filter(b -> b instanceof ParagraphBlock).count();
        long codeCount = blocks.stream().filter(b -> b instanceof CodeBlock).count();
        long listCount = blocks.stream().filter(b -> b instanceof ListBlock).count();
        long tableCount = blocks.stream().filter(b -> b instanceof TableBlock).count();

        System.out.printf("[3a/5] MD 解析结果: heading=%d, para=%d, code=%d, list=%d, table=%d%n",
                headingCount, paragraphCount, codeCount, listCount, tableCount);

        assertTrue(headingCount >= 2, "至少 2 个标题");
        assertTrue(paragraphCount >= 2, "至少 2 个段落");
        assertTrue(codeCount >= 1 || listCount >= 1 || tableCount >= 1, "至少 1 个结构化 Block");
    }

    @Test
    @Order(4)
    void shouldParseTextWithTika() {
        // 用 application/xml 确保只命中 Tika（Markdown 也匹配 text/plain）
        String text = "<root><item>第一行</item><item>第二行</item></root>";
        DocumentParser tika = parserSelector.selectByMimeType("application/xml");
        assertNotNull(tika, "application/xml 应有解析器");
        assertTrue(tika instanceof TikaDocumentParser, "application/xml 应命中 Tika");

        ParsedDocument doc = tika.parseStructured(
                text.getBytes(StandardCharsets.UTF_8), "application/xml", Map.of());
        assertNotNull(doc.blocks());
        System.out.println("[4/5] Tika 解析 (application/xml): " + doc.blocks().size() + " 个 block");
    }

    // ============= 分块管道集成测试 =============

    @Test
    @Order(5)
    void shouldChunkMarkdownViaService() {
        String md = """
                # 第一章

                这是第一章的第一段内容，用来测试分块效果。

                这是第一章的第二段内容。

                ## 1.1 小节

                小节的内容比较简单。

                ## 1.2 小节

                这小节的内容稍微长一点，包含更多的文字用于测试分块器按字符数切分的效果。再多写一些文字让这个段落足够长。

                ```python
                def hello():
                    print("hello world")
                ```

                - 列表项 A
                - 列表项 B
                - 列表项 C
                """;

        // 解析
        DocumentParser parser = parserSelector.select("Markdown");
        ParsedDocument parsed = parser.parseStructured(
                md.getBytes(StandardCharsets.UTF_8), "text/markdown", Map.of());

        // StructureAware 分块（block-aware）
        List<VectorChunk> chunks = structuredChunkingService.chunk(
                parsed.blocks(), null,
                ChunkingMode.STRUCTURE_AWARE,
                ChunkingMode.STRUCTURE_AWARE.createOptions(Map.of()),
                null);

        System.out.println("[5/5] Block-aware 分块: " + chunks.size() + " 个 chunk");
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunk c = chunks.get(i);
            System.out.printf("  chunk[%d] type=%s outline=%s content=%s%n",
                    i, c.getBlockType(), c.getOutlinePath(),
                    c.getContent().length() > 60 ? c.getContent().substring(0, 60) + "..." : c.getContent());
        }

        assertFalse(chunks.isEmpty(), "分块结果不应为空");
        // 应有 CODE 和 LIST 类型的 block
        assertTrue(chunks.stream().anyMatch(c -> "CODE".equals(c.getBlockType())), "应有代码块");
        assertTrue(chunks.stream().anyMatch(c -> "LIST".equals(c.getBlockType())), "应有列表块");
        // heading 应累积到 outlinePath
        assertTrue(chunks.stream().anyMatch(c -> !c.getOutlinePath().isEmpty()), "应有 outlinePath");

        // FixedSize 对比
        List<VectorChunk> fixedChunks = fixedSizeTextChunker.chunk(md,
                ChunkingMode.FIXED_SIZE.createOptions(Map.of()));
        System.out.println("  FixedSize 对比: " + fixedChunks.size() + " 个 chunk");

        // StructureAware legacy 对比（纯文本，不用 block-aware）
        List<VectorChunk> legacyChunks = structureAwareTextChunker.chunk(md,
                ChunkingMode.STRUCTURE_AWARE.createOptions(Map.of()));
        System.out.println("  StructureAware legacy 对比: " + legacyChunks.size() + " 个 chunk");
    }

    // ============= chunk-strategies 端点 =============

    @Test
    @Order(6)
    void shouldListChunkStrategies() {
        assertNotNull(token);
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        ResponseEntity<Result> r = restTemplate.exchange(
                url("/knowledge-base/chunk-strategies"),
                HttpMethod.GET, new HttpEntity<>(h), Result.class);

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(r.getBody().isSuccess());
        @SuppressWarnings("unchecked")
        var strategies = (List<Map<String, Object>>) r.getBody().getData();
        assertNotNull(strategies);
        assertFalse(strategies.isEmpty());
        System.out.println("[6/6] chunk-strategies: " + strategies.size() + " 个策略");
        strategies.forEach(s -> System.out.println("  " + s.get("value") + " → " + s.get("label")));
    }
}
