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

package com.xinshuo.mindflow.ingestion.engine;

import cn.hutool.core.util.StrUtil;
import com.xinshuo.mindflow.framework.exception.ClientException;
import com.xinshuo.mindflow.ingestion.domain.context.IngestionContext;
import com.xinshuo.mindflow.ingestion.domain.context.NodeLog;
import com.xinshuo.mindflow.ingestion.domain.enums.IngestionStatus;
import com.xinshuo.mindflow.ingestion.domain.pipeline.NodeConfig;
import com.xinshuo.mindflow.ingestion.domain.pipeline.PipelineDefinition;
import com.xinshuo.mindflow.ingestion.domain.result.NodeResult;
import com.xinshuo.mindflow.ingestion.node.IngestionNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 流水线执行引擎——基于 nextNodeId 的链式执行
 */
@Slf4j
@Component
public class IngestionEngine {

    private final Map<String, IngestionNode> nodeMap;
    private final ConditionEvaluator conditionEvaluator;
    private final NodeOutputExtractor outputExtractor;

    public IngestionEngine(List<IngestionNode> nodes,
                           ConditionEvaluator conditionEvaluator,
                           NodeOutputExtractor outputExtractor) {
        this.nodeMap = nodes.stream()
                .collect(Collectors.toMap(IngestionNode::getNodeType, n -> n));
        this.conditionEvaluator = conditionEvaluator;
        this.outputExtractor = outputExtractor;
    }

    public IngestionContext execute(PipelineDefinition pipeline, IngestionContext context) {
        if (context.getLogs() == null) {
            context.setLogs(new ArrayList<>());
        }
        context.setStatus(IngestionStatus.RUNNING);

        Map<String, NodeConfig> nodeConfigMap = buildNodeConfigMap(pipeline.getNodes());
        validatePipeline(nodeConfigMap);

        String startNodeId = findStartNode(nodeConfigMap);
        if (StrUtil.isBlank(startNodeId)) {
            throw new ClientException("流水线未找到起始节点");
        }

        log.info("流水线从节点开始执行: {}", startNodeId);
        executeChain(startNodeId, nodeConfigMap, context);

        if (context.getStatus() == IngestionStatus.RUNNING) {
            context.setStatus(IngestionStatus.COMPLETED);
        }
        return context;
    }

    private Map<String, NodeConfig> buildNodeConfigMap(List<NodeConfig> nodes) {
        if (nodes == null) {
            return Collections.emptyMap();
        }
        return nodes.stream()
                .collect(Collectors.toMap(NodeConfig::getNodeId, n -> n));
    }

    private void validatePipeline(Map<String, NodeConfig> nodeConfigMap) {
        Set<String> visited = new HashSet<>();
        for (String nodeId : nodeConfigMap.keySet()) {
            if (visited.contains(nodeId)) {
                continue;
            }
            Set<String> path = new HashSet<>();
            String current = nodeId;
            while (current != null) {
                if (path.contains(current)) {
                    throw new ClientException("流水线存在环: " + current);
                }
                path.add(current);
                visited.add(current);

                NodeConfig config = nodeConfigMap.get(current);
                if (config == null) {
                    break;
                }
                String nextId = config.getNextNodeId();
                if (StringUtils.hasText(nextId)) {
                    if (!nodeConfigMap.containsKey(nextId)) {
                        throw new ClientException("找不到下一个节点: " + nextId + "，被节点 " + current + " 引用");
                    }
                    current = nextId;
                } else {
                    break;
                }
            }
        }
    }

    private String findStartNode(Map<String, NodeConfig> nodeConfigMap) {
        Set<String> referencedNodes = nodeConfigMap.values().stream()
                .map(NodeConfig::getNextNodeId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        return nodeConfigMap.keySet().stream()
                .filter(nodeId -> !referencedNodes.contains(nodeId))
                .findFirst()
                .orElse(null);
    }

    private void executeChain(String nodeId,
                              Map<String, NodeConfig> nodeConfigMap,
                              IngestionContext context) {
        String currentNodeId = nodeId;
        int executedCount = 0;
        final int maxNodes = nodeConfigMap.size();

        while (currentNodeId != null) {
            if (executedCount++ > maxNodes) {
                throw new ClientException("执行节点数超过上限，可能存在死循环");
            }
            NodeConfig config = nodeConfigMap.get(currentNodeId);
            if (config == null) {
                log.warn("未找到节点配置: {}", currentNodeId);
                break;
            }

            log.info("开始执行节点: {}", currentNodeId);
            NodeResult result = executeNode(context, config);

            if (!result.isSuccess()) {
                context.setStatus(IngestionStatus.FAILED);
                context.setError(result.getError());
                log.error("节点 {} 执行失败: {}", currentNodeId, result.getMessage());
                break;
            }
            if (!result.isShouldContinue()) {
                log.info("流水线在节点 {} 停止", currentNodeId);
                break;
            }
            currentNodeId = config.getNextNodeId();
        }

        log.info("流水线执行完成，共执行 {} 个节点", executedCount);
    }

    private NodeResult executeNode(IngestionContext context, NodeConfig nodeConfig) {
        String nodeType = nodeConfig.getNodeType();
        String nodeId = nodeConfig.getNodeId();

        IngestionNode node = nodeMap.get(nodeType);
        if (node == null) {
            return NodeResult.fail(new IllegalStateException("未找到节点类型: " + nodeType));
        }

        if (nodeConfig.getCondition() != null && !nodeConfig.getCondition().isNull()) {
            if (!conditionEvaluator.evaluate(context, nodeConfig.getCondition())) {
                NodeResult skip = NodeResult.skip("条件未满足");
                context.getLogs().add(NodeLog.builder()
                        .nodeId(nodeId)
                        .nodeType(nodeType)
                        .message(skip.getMessage())
                        .durationMs(0)
                        .success(true)
                        .output(outputExtractor.extract(context, nodeConfig))
                        .build());
                return skip;
            }
        }

        long start = System.currentTimeMillis();
        try {
            NodeResult result = node.execute(context, nodeConfig);
            long duration = System.currentTimeMillis() - start;

            context.getLogs().add(NodeLog.builder()
                    .nodeId(nodeId)
                    .nodeType(nodeType)
                    .message(result.getMessage())
                    .durationMs(duration)
                    .success(result.isSuccess())
                    .error(result.getError() == null ? null : result.getError().getMessage())
                    .output(outputExtractor.extract(context, nodeConfig))
                    .build());

            log.info("节点 {} 执行完成，耗时 {}ms: {}", nodeId, duration, result.getMessage());
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            context.getLogs().add(NodeLog.builder()
                    .nodeId(nodeId)
                    .nodeType(nodeType)
                    .message(e.getMessage())
                    .durationMs(duration)
                    .success(false)
                    .error(e.getMessage())
                    .output(outputExtractor.extract(context, nodeConfig))
                    .build());
            log.error("节点 {} 执行失败，耗时 {}ms", nodeId, duration, e);
            return NodeResult.fail(e);
        }
    }
}
