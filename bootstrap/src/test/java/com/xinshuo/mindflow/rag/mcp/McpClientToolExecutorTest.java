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

import com.xinshuo.mindflow.rag.core.mcp.McpClientToolExecutor;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MCP 客户端工具执行器单元测试（mock McpSyncClient，验证远程调用与异常降级）
 */
class McpClientToolExecutorTest {

    private final Tool tool = Tool.builder().name("sales_query").description("销售查询").build();

    @Test
    void shouldCallRemoteToolSuccessfully() {
        McpSyncClient client = mock(McpSyncClient.class);
        CallToolResult success = CallToolResult.builder()
                .content(List.of(new TextContent("查询结果")))
                .isError(false)
                .build();
        when(client.callTool(any(CallToolRequest.class))).thenReturn(success);

        McpClientToolExecutor executor = new McpClientToolExecutor(client, tool);
        CallToolResult result = executor.execute(Map.of("region", "华东", "period", "本月"));

        assertNotNull(result);
        assertFalse(result.isError(), "正常调用不应报错");
        assertEquals("sales_query", executor.getToolId(), "toolId 应取自工具定义");
        verify(client, times(1)).callTool(any(CallToolRequest.class));
    }

    @Test
    void shouldReturnErrorResultOnRemoteException() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any(CallToolRequest.class)))
                .thenThrow(new RuntimeException("连接超时"));

        McpClientToolExecutor executor = new McpClientToolExecutor(client, tool);
        CallToolResult result = executor.execute(Map.of("city", "北京"));

        assertNotNull(result);
        assertTrue(result.isError(), "远程异常应标记 isError");
        assertNotNull(result.content(), "错误结果应含文本内容");
        assertEquals("sales_query", executor.getToolId());
    }

    @Test
    void shouldHandleNullParameters() {
        McpSyncClient client = mock(McpSyncClient.class);
        CallToolResult success = CallToolResult.builder().isError(false).build();
        when(client.callTool(any(CallToolRequest.class))).thenReturn(success);

        McpClientToolExecutor executor = new McpClientToolExecutor(client, tool);
        CallToolResult result = executor.execute(null);

        assertNotNull(result, "null 参数应被处理为空 Map");
        assertFalse(result.isError());
    }
}
