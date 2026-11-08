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

package com.xinshuo.mindflow.ingestion.node;

import com.xinshuo.mindflow.framework.exception.ClientException;
import com.xinshuo.mindflow.ingestion.domain.context.DocumentSource;
import com.xinshuo.mindflow.ingestion.domain.context.IngestionContext;
import com.xinshuo.mindflow.ingestion.domain.enums.IngestionNodeType;
import com.xinshuo.mindflow.ingestion.domain.enums.SourceType;
import com.xinshuo.mindflow.ingestion.domain.pipeline.NodeConfig;
import com.xinshuo.mindflow.ingestion.domain.result.NodeResult;
import com.xinshuo.mindflow.ingestion.strategy.fetcher.DocumentFetcher;
import com.xinshuo.mindflow.ingestion.strategy.fetcher.FetchResult;
import com.xinshuo.mindflow.ingestion.util.MimeTypeDetector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档获取节点——按 {@link SourceType} 路由到具体 {@link DocumentFetcher}
 */
@Component
public class FetcherNode implements IngestionNode {

    private final Map<SourceType, DocumentFetcher> fetchers;

    public FetcherNode(List<DocumentFetcher> fetchers) {
        this.fetchers = fetchers.stream()
                .collect(Collectors.toMap(DocumentFetcher::supportedType, Function.identity()));
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.FETCHER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        if (context.getRawBytes() != null && context.getRawBytes().length > 0) {
            if (!StringUtils.hasText(context.getMimeType())) {
                String fileName = context.getSource() == null ? null : context.getSource().getFileName();
                context.setMimeType(MimeTypeDetector.detect(context.getRawBytes(), fileName));
            }
            return NodeResult.ok("已跳过获取器：原始字节已存在");
        }

        DocumentSource source = context.getSource();
        if (source == null || source.getType() == null) {
            return NodeResult.fail(new ClientException("文档来源不能为空"));
        }

        DocumentFetcher fetcher = fetchers.get(source.getType());
        if (fetcher == null) {
            return NodeResult.fail(new ClientException("不支持的来源类型: " + source.getType()));
        }

        FetchResult result = fetcher.fetch(source);
        context.setRawBytes(result.content());
        if (StringUtils.hasText(result.mimeType())) {
            context.setMimeType(result.mimeType());
        }
        if (StringUtils.hasText(result.fileName())) {
            source.setFileName(result.fileName());
        }
        return NodeResult.ok("已获取 " + (result.content() == null ? 0 : result.content().length) + " 字节");
    }
}
