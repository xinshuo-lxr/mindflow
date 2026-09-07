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

import com.xinshuo.mindflow.rag.core.mcp.DefaultMcpToolRegistry;
import com.xinshuo.mindflow.rag.core.mcp.McpToolExecutor;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 工具注册表单元测试（纯逻辑，无 Spring）
 */
class DefaultMcpToolRegistryTest {

    private final DefaultMcpToolRegistry registry = new DefaultMcpToolRegistry(List.of());

    private McpToolExecutor executor(String toolId) {
        return new McpToolExecutor() {
            @Override
            public Tool getToolDefinition() {
                return Tool.builder().name(toolId).description(toolId + " 描述").build();
            }

            @Override
            public CallToolResult execute(Map<String, Object> parameters) {
                return CallToolResult.builder().isError(false).build();
            }
        };
    }

    @Test
    void shouldRegisterAndFindExecutor() {
        registry.register(executor("sales_query"));

        assertTrue(registry.contains("sales_query"), "注册后应能查到");
        assertEquals(1, registry.size());
        assertTrue(registry.getExecutor("sales_query").isPresent(), "应能获取执行器");
        assertEquals("sales_query", registry.getExecutor("sales_query").get().getToolId());
        registry.unregister("sales_query");
        assertFalse(registry.contains("sales_query"), "注销后不应查到");
    }

    @Test
    void shouldListAllTools() {
        registry.register(executor("tool_a"));
        registry.register(executor("tool_b"));

        List<Tool> tools = registry.listAllTools();
        assertEquals(2, tools.size(), "应列出 2 个工具定义");
        assertTrue(tools.stream().anyMatch(t -> "tool_a".equals(t.name())));
        assertTrue(tools.stream().anyMatch(t -> "tool_b".equals(t.name())));
    }

    @Test
    void shouldOverwriteOnSameToolId() {
        registry.register(executor("dup"));
        registry.register(executor("dup"));

        assertEquals(1, registry.size(), "相同 toolId 应覆盖而非新增");
        assertEquals(1, registry.listAllExecutors().size());
    }

    @Test
    void shouldIgnoreNullExecutor() {
        registry.register(null);
        assertEquals(0, registry.size(), "null 执行器应被忽略");
    }

    @Test
    void shouldReturnEmptyForMissingExecutor() {
        assertTrue(registry.getExecutor("nonexistent").isEmpty());
        assertFalse(registry.contains("nonexistent"));
    }
}
