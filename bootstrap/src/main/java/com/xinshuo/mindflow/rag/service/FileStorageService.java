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

package com.xinshuo.mindflow.rag.service;

import com.xinshuo.mindflow.rag.dto.StoredFileDTO;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface FileStorageService {

    /**
     * 上传文件（流式，低内存）
     */
    StoredFileDTO upload(String bucketName, MultipartFile file);

    /**
     * 上传文件（流式，低内存）
     */
    StoredFileDTO upload(String bucketName, InputStream content, long size, String originalFilename, String contentType);

    /**
     * 上传文件
     */
    StoredFileDTO upload(String bucketName, byte[] content, String originalFilename, String contentType);

    /**
     * 上传文件（带自动重试）
     */
    StoredFileDTO reliableUpload(String bucketName, InputStream content, long size, String originalFilename, String contentType);

    InputStream openStream(String url);

    void deleteByUrl(String url);

    /**
     * 判断 bucket 是否存在
     *
     * @param bucket bucket 名
     * @return 存在返回 true,不存在返回 false
     */
    boolean bucketExists(String bucket);

    /**
     * 创建 bucket(幂等:已存在视为成功)
     *
     * @param bucket bucket 名
     */
    void createBucket(String bucket);

    /**
     * 把内部存储定位符转为浏览器可直连的公开预览 URL
     *
     * @param url 内部 {@code file://bucket/key} 定位符
     * @return 公开 HTTP URL
     */
    String getPublicUrl(String url);

    /**
     * 给 bucket 下发公共读策略,幂等
     *
     * @param bucket bucket 名
     */
    void setBucketPublicReadOnly(String bucket);
}
