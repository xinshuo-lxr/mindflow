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

package com.xinshuo.mindflow.ingestion.strategy.fetcher;

import com.xinshuo.mindflow.framework.exception.ServiceException;
import com.xinshuo.mindflow.ingestion.domain.context.DocumentSource;
import com.xinshuo.mindflow.ingestion.domain.enums.SourceType;
import com.xinshuo.mindflow.ingestion.util.MimeTypeDetector;
import com.xinshuo.mindflow.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;

/**
 * S3 对象存储文档抓取器，示例路径：s3://biz/xxx.md
 */
@Component
@RequiredArgsConstructor
public class S3Fetcher implements DocumentFetcher {

    private final FileStorageService fileStorageService;

    @Override
    public SourceType supportedType() {
        return SourceType.S3;
    }

    @Override
    public FetchResult fetch(DocumentSource source) {
        String location = source.getLocation();
        if (!StringUtils.hasText(location)) {
            throw new ServiceException("S3路径不能为空");
        }
        if (!location.startsWith("s3://")) {
            throw new ServiceException("无效的S3路径格式，应以 s3:// 开头: " + location);
        }

        try {
            byte[] bytes;
            try (InputStream is = fileStorageService.openStream(location)) {
                bytes = is.readAllBytes();
            }

            String fileName = source.getFileName();
            if (!StringUtils.hasText(fileName)) {
                fileName = extractFileName(location);
            }

            String mimeType = MimeTypeDetector.detect(bytes, fileName);
            return new FetchResult(bytes, mimeType, fileName);
        } catch (Exception e) {
            throw new ServiceException("从S3读取文件失败: " + location + ", 错误: " + e.getMessage());
        }
    }

    private String extractFileName(String location) {
        int idx = location.lastIndexOf('/');
        return idx >= 0 ? location.substring(idx + 1) : location;
    }
}
