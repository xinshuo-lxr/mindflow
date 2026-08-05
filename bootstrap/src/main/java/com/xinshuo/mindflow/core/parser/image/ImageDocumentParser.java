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

package com.xinshuo.mindflow.core.parser.image;

import com.xinshuo.mindflow.core.parser.DocumentParser;
import com.xinshuo.mindflow.core.parser.ParserType;
import com.xinshuo.mindflow.core.parser.model.AssetRef;
import com.xinshuo.mindflow.core.parser.model.ImageBlock;
import com.xinshuo.mindflow.core.parser.model.ParsedDocument;
import com.xinshuo.mindflow.core.parser.model.Provenance;
import com.xinshuo.mindflow.framework.exception.ServiceException;
import com.xinshuo.mindflow.infra.vlm.VlmService;
import com.xinshuo.mindflow.rag.dto.StoredFileDTO;
import com.xinshuo.mindflow.rag.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 图片文档解析器（PNG / JPG）
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class ImageDocumentParser implements DocumentParser {

    private final VlmService vlmService;
    private final FileStorageService fileStorageService;
    private final ImageParseProperties properties;
    private final String assetBucket;

    public ImageDocumentParser(VlmService vlmService,
                               FileStorageService fileStorageService,
                               ImageParseProperties properties,
                               @Value("${mindflow.storage.asset-bucket:mindflow-assets}") String assetBucket) {
        this.vlmService = vlmService;
        this.fileStorageService = fileStorageService;
        this.properties = properties;
        this.assetBucket = assetBucket;
    }

    @Override
    public String getParserType() { return ParserType.IMAGE.getType(); }

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) return false;
        String lower = mimeType.toLowerCase(Locale.ROOT);
        return lower.equals("image/png") || lower.equals("image/jpeg") || lower.equals("image/jpg");
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) throw new ServiceException("图片解析输入字节为空");
        String sourceFile = extract(options, "sourceFile", "");
        String docId = extract(options, "documentId", UUID.randomUUID().toString());

        String description = vlmService.describeImage(content, mimeType,
                properties.getDescriptionPrompt(), properties.getMaxOutputTokens());
        description = description == null ? "" : description.strip();
        if (description.isBlank()) throw new ServiceException("VLM 返回空描述：" + sourceFile);

        String ext = extFromMime(mimeType);
        String filename = "assets/" + docId + "/" + UUID.randomUUID() + "." + ext;
        if (!fileStorageService.bucketExists(assetBucket)) fileStorageService.createBucket(assetBucket);
        StoredFileDTO stored = fileStorageService.upload(assetBucket, content, filename, mimeType);
        String publicUrl = fileStorageService.getPublicUrl(stored.getUrl());

        String blockId = UUID.randomUUID().toString();
        String caption = stripExt(sourceFile);
        AssetRef asset = new AssetRef(publicUrl, mimeType, blockId);
        ImageBlock block = new ImageBlock(blockId, Provenance.ofFile(sourceFile), List.of(), asset, caption, caption, description);

        log.info("图片图生文完成: file={}, descChars={}", sourceFile, description.length());
        return ParsedDocument.of(List.of(block), Map.of("parser", getParserType(), "mimeType",
                mimeType == null ? "" : mimeType, "descriptionChars", description.length()));
    }

    private static String extract(Map<String, Object> opts, String key, String def) {
        if (opts == null) return def;
        Object v = opts.get(key);
        return (v == null || v.toString().isBlank()) ? def : v.toString();
    }

    private static String extFromMime(String m) {
        if (m == null) return "png";
        return switch (m.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> "jpg";
            default -> "png";
        };
    }

    private static String stripExt(String name) {
        if (name == null || name.isBlank()) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
