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

package com.xinshuo.mindflow.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinshuo.mindflow.framework.exception.ClientException;
import com.xinshuo.mindflow.ingestion.domain.context.IngestionContext;
import com.xinshuo.mindflow.ingestion.domain.enums.IngestionStatus;
import com.xinshuo.mindflow.ingestion.domain.pipeline.NodeConfig;
import com.xinshuo.mindflow.ingestion.domain.pipeline.PipelineDefinition;
import com.xinshuo.mindflow.ingestion.domain.result.NodeResult;
import com.xinshuo.mindflow.ingestion.engine.ConditionEvaluator;
import com.xinshuo.mindflow.ingestion.engine.IngestionEngine;
import com.xinshuo.mindflow.ingestion.engine.NodeOutputExtractor;
import com.xinshuo.mindflow.ingestion.node.IngestionNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 摄取引擎单元测试（纯逻辑，无 Spring）
 *
 * <p>验证链式执行、条件跳过、失败终止、环检测、起始节点推导。
 */
class IngestionEngineTest {

    /**
     * 记录执行顺序的测试节点
     */
    private static final class RecordingNode implements IngestionNode {
        private final String type;
        private final List<String> trace;
        private final NodeResult result;

        RecordingNode(String type, List<String> trace) {
            this(type, trace, NodeResult.ok("executed " + type));
        }

        RecordingNode(String type, List<String> trace, NodeResult result) {
            this.type = type;
            this.trace = trace;
            this.result = result;
        }

        @Override
        public String getNodeType() {
            return type;
        }

        @Override
        public NodeResult execute(IngestionContext context, NodeConfig config) {
            trace.add(type + ":" + config.getNodeId());
            return result;
        }
    }

    private IngestionEngine engineWith(List<String> trace, NodeResult failResult) {
        List<IngestionNode> nodes = new ArrayList<>();
        nodes.add(new RecordingNode("n1", trace));
        nodes.add(new RecordingNode("n2", trace, failResult));
        nodes.add(new RecordingNode("n3", trace));
        return new IngestionEngine(nodes, new ConditionEvaluator(new ObjectMapper()), new NodeOutputExtractor());
    }

    private NodeConfig node(String id, String type, String next) {
        return NodeConfig.builder().nodeId(id).nodeType(type).nextNodeId(next).build();
    }

    @Test
    void shouldExecuteChainInOrder() {
        List<String> trace = new ArrayList<>();
        IngestionNode okNode = new RecordingNode("n1", trace);
        IngestionEngine engine = new IngestionEngine(
                List.of(okNode, new RecordingNode("n2", trace)),
                new ConditionEvaluator(new ObjectMapper()),
                new NodeOutputExtractor());

        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(node("a", "n1", "b"), node("b", "n2", null)))
                .build();

        IngestionContext result = engine.execute(pipeline, IngestionContext.builder().build());

        assertEquals(List.of("n1:a", "n2:b"), trace, "节点应按 nextNodeId 顺序执行");
        assertEquals(IngestionStatus.COMPLETED, result.getStatus());
        assertEquals(2, result.getLogs().size(), "每个节点应产生一条日志");
    }

    @Test
    void shouldSkipNodeWhenConditionFalse() {
        List<String> trace = new ArrayList<>();
        IngestionEngine engine = new IngestionEngine(
                List.of(new RecordingNode("n1", trace), new RecordingNode("n2", trace)),
                new ConditionEvaluator(new ObjectMapper()),
                new NodeOutputExtractor());

        NodeConfig first = node("a", "n1", "b");
        first.setCondition(new ObjectMapper().getNodeFactory().booleanNode(false));
        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(first, node("b", "n2", null)))
                .build();

        IngestionContext result = engine.execute(pipeline, IngestionContext.builder().build());

        assertEquals(List.of("n2:b"), trace, "条件为 false 的节点不应执行");
        assertEquals(2, result.getLogs().size(), "跳过也应有日志");
        assertEquals("Skipped: 条件未满足", result.getLogs().get(0).getMessage());
    }

    @Test
    void shouldStopChainOnFailure() {
        List<String> trace = new ArrayList<>();
        IngestionEngine engine = engineWith(trace, NodeResult.fail(new IllegalStateException("boom")));

        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(node("a", "n1", "b"), node("b", "n2", "c"), node("c", "n3", null)))
                .build();

        IngestionContext result = engine.execute(pipeline, IngestionContext.builder().build());

        assertEquals(List.of("n1:a", "n2:b"), trace, "失败后不应继续执行后续节点");
        assertEquals(IngestionStatus.FAILED, result.getStatus());
        assertNotNull(result.getError());
        assertEquals("boom", result.getError().getMessage());
    }

    @Test
    void shouldRejectCyclicPipeline() {
        IngestionEngine engine = new IngestionEngine(
                List.of(new RecordingNode("n1", new ArrayList<>())),
                new ConditionEvaluator(new ObjectMapper()),
                new NodeOutputExtractor());

        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(node("a", "n1", "b"), node("b", "n1", "a")))
                .build();

        assertThrows(ClientException.class, () -> engine.execute(pipeline, IngestionContext.builder().build()));
    }

    @Test
    void shouldRejectMissingNextNode() {
        IngestionEngine engine = new IngestionEngine(
                List.of(new RecordingNode("n1", new ArrayList<>())),
                new ConditionEvaluator(new ObjectMapper()),
                new NodeOutputExtractor());

        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(node("a", "n1", "not-exist")))
                .build();

        ClientException ex = assertThrows(ClientException.class,
                () -> engine.execute(pipeline, IngestionContext.builder().build()));
        assertTrue(ex.getMessage().contains("找不到下一个节点"));
    }
}
