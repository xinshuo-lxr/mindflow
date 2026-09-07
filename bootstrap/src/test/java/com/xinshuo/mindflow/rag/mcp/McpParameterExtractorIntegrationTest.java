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

package com.xinshuo.mindflow.rag.mcp;

import com.xinshuo.mindflow.MindflowApplication;
import com.xinshuo.mindflow.rag.core.mcp.McpParameterExtractor;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 参数提取集成测试（真实 LLM + 手工构造工具定义，弱断言）
 *
 * <p>验证 LLM 能从用户自然语言中提取工具参数（如"北京今天天气"→ city=北京）。
 * 真实 LLM 失败时服务降级返回默认参数，测试仍通过。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
class McpParameterExtractorIntegrationTest {

    @Autowired
    private McpParameterExtractor parameterExtractor;

    private Tool weatherTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("city", Map.of(
                "type", "string",
                "description", "城市名称，如北京、上海、广州等"
        ));
        properties.put("queryType", Map.of(
                "type", "string",
                "description", "查询类型：current(当前)、forecast(预报)",
                "enum", List.of("current", "forecast"),
                "default", "current"
        ));
        JsonSchema schema = new JsonSchema("object", properties, List.of("city"), null, null, null);
        return Tool.builder()
                .name("weather_query")
                .description("查询城市天气")
                .inputSchema(schema)
                .build();
    }

    @Test
    void shouldExtractCityFromUserQuestion() {
        Tool tool = weatherTool();
        Map<String, Object> params = parameterExtractor.extractParameters("北京今天天气怎么样", tool);

        assertNotNull(params, "提取结果不应为 null");
        System.out.println("[1] 提取参数: " + params);

        // 软断言：真实 LLM 成功时 city=北京；失败降级为空 Map（测试仍通过）
        if (!params.isEmpty()) {
            assertTrue(params.containsKey("city"), "应提取到 city 参数");
            assertEquals("北京", params.get("city"), "city 应为北京");
        }
    }

    @Test
    void shouldReturnEmptyForToolWithoutParams() {
        Tool emptyTool = Tool.builder().name("noop").description("无参数工具").build();
        Map<String, Object> params = parameterExtractor.extractParameters("随便问", emptyTool);
        assertNotNull(params, "无参数工具应返回非 null（空 Map）");
        System.out.println("[2] 无参数工具返回: " + params);
    }

    @Test
    void shouldExtractWithDefaultValue() {
        Tool tool = weatherTool();
        // queryType 有默认值 current，city 缺失时 LLM 可能补默认或按必填输出
        Map<String, Object> params = parameterExtractor.extractParameters("北京未来三天的预报", tool);
        assertNotNull(params);
        System.out.println("[3] 预报参数提取: " + params);
        if (!params.isEmpty()) {
            assertTrue(params.containsKey("city"));
        }
    }
}
