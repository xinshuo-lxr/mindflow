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

package com.xinshuo.mindflow.rag.service.impl;

import cn.hutool.core.lang.Assert;
import com.xinshuo.mindflow.rag.dto.StoredFileDTO;
import com.xinshuo.mindflow.rag.service.FileStorageService;
import com.xinshuo.mindflow.rag.util.FileTypeDetector;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 本地文件存储服务
 * 第 7 步使用本地文件系统，后续可替换为 S3FileStorageService
 */
@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

    private static final Tika TIKA = new Tika();

    @Value("${mindflow.storage.root:./mindflow-data}")
    private String storageRoot;

    @Override
    public StoredFileDTO upload(String bucketName, MultipartFile file) {
        validateBucketName(bucketName);
        Assert.isFalse(file == null || file.isEmpty(), "上传文件不能为空");

        String originalFilename = file.getOriginalFilename();
        long size = file.getSize();

        String detectedContentType;
        try (InputStream is = file.getInputStream()) {
            detectedContentType = TIKA.detect(is, originalFilename);
        } catch (IOException e) {
            throw new RuntimeException("读取上传文件失败", e);
        }

        try (InputStream is = file.getInputStream()) {
            return streamUpload(bucketName, is, size, originalFilename, detectedContentType);
        } catch (IOException e) {
            throw new RuntimeException("文件流式上传失败", e);
        }
    }

    @Override
    public StoredFileDTO upload(String bucketName, InputStream content, long size, String originalFilename, String contentType) {
        validateBucketName(bucketName);
        Assert.notNull(content, "上传内容不能为空");
        Assert.isTrue(size >= 0, "上传内容大小不能小于0");
        String detected = resolveContentType(originalFilename, contentType);
        return streamUpload(bucketName, content, size, originalFilename, detected);
    }

    @Override
    public StoredFileDTO upload(String bucketName, byte[] content, String originalFilename, String contentType) {
        validateBucketName(bucketName);
        Assert.notNull(content, "上传内容不能为空");
        String detected = resolveContentType(originalFilename, contentType);
        return streamUpload(bucketName, new ByteArrayInputStream(content), content.length, originalFilename, detected);
    }

    @Override
    public StoredFileDTO reliableUpload(String bucketName, InputStream content, long size, String originalFilename, String contentType) {
        return upload(bucketName, content, size, originalFilename, contentType);
    }

    @Override
    public InputStream openStream(String url) {
        Path path = parseFileUrl(url);
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + url, e);
        }
    }

    @Override
    public void deleteByUrl(String url) {
        Path path = parseFileUrl(url);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除文件失败: {}", url, e);
        }
    }

    @Override
    public boolean bucketExists(String bucket) {
        validateBucketName(bucket);
        Path bucketPath = resolveBucketPath(bucket);
        return Files.isDirectory(bucketPath);
    }

    @Override
    public void createBucket(String bucket) {
        validateBucketName(bucket);
        Path bucketPath = resolveBucketPath(bucket);
        try {
            Files.createDirectories(bucketPath);
        } catch (IOException e) {
            throw new RuntimeException("创建bucket失败: " + bucket, e);
        }
    }

    @Override
    public String getPublicUrl(String url) {
        return url;
    }

    @Override
    public void setBucketPublicReadOnly(String bucket) {
        // 本地存储不需要设置权限
    }

    private StoredFileDTO streamUpload(String bucketName, InputStream inputStream,
                                        long size, String originalFilename,
                                        String detectedContentType) {
        String fileKey = generateFileKey(originalFilename);
        Path bucketPath = resolveBucketPath(bucketName);
        try {
            Files.createDirectories(bucketPath);
            Path targetPath = bucketPath.resolve(fileKey);
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("文件写入失败", e);
        }

        String url = toFileUrl(bucketName, fileKey);
        return buildStoredFileDTO(url, originalFilename, detectedContentType, size);
    }

    private Path resolveBucketPath(String bucketName) {
        return Paths.get(storageRoot, bucketName).toAbsolutePath().normalize();
    }

    private Path parseFileUrl(String url) {
        if (url.startsWith("file:///")) {
            return Paths.get(url.substring("file:///".length()));
        }
        if (url.startsWith("file://")) {
            return Paths.get(url.substring("file://".length()));
        }
        // 兼容相对路径
        return Paths.get(url);
    }

    private String toFileUrl(String bucket, String key) {
        Path abs = resolveBucketPath(bucket).resolve(key).toAbsolutePath().normalize();
        return "file://" + abs.toString().replace('\\', '/');
    }

    private String extractSuffix(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return (idx < 0 || idx == filename.length() - 1) ? "" : filename.substring(idx + 1).trim();
    }

    private String generateFileKey(String originalFilename) {
        String suffix = extractSuffix(originalFilename);
        UUID uuid = UUID.randomUUID();
        String key = String.format("%016x%016x", uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
        return suffix.isBlank() ? key : key + "." + suffix;
    }

    private void validateBucketName(String bucketName) {
        Assert.notBlank(bucketName, "bucketName 不能为空");
    }

    private StoredFileDTO buildStoredFileDTO(String url, String originalFilename,
                                              String contentType, long size) {
        String detectedType = FileTypeDetector.detectType(originalFilename, contentType);
        return StoredFileDTO.builder()
                .url(url)
                .detectedType(detectedType)
                .mimeType(contentType)
                .size(size)
                .originalFilename(originalFilename)
                .build();
    }

    private String resolveContentType(String originalFilename, String contentType) {
        if (contentType != null && !contentType.isBlank()) return contentType;
        if (originalFilename != null && !originalFilename.isBlank()) return TIKA.detect(originalFilename);
        return null;
    }
}
