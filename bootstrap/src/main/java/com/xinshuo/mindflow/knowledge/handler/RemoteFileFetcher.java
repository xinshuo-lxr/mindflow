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

package com.xinshuo.mindflow.knowledge.handler;

import com.xinshuo.mindflow.framework.exception.ClientException;
import com.xinshuo.mindflow.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 远程文件拉取器
 * <p>
 * 定时刷新场景：先 HEAD 比对 ETag / Last-Modified，再流式下载并计算 SHA-256，
 * 任一级等价即判定未变化，避免无意义的分块与向量重建。
 * <p>
 * 注意：{@link RemoteFetchResult} 实现了 {@link AutoCloseable}，调用方必须
 * try-with-resources 管理临时文件生命周期。
 */
@Slf4j
@Component
public class RemoteFileFetcher {

    private static final int BUFFER_SIZE = 8192;

    private final OkHttpClient httpClient;

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private DataSize maxFileSize;

    public RemoteFileFetcher(@Qualifier("syncHttpClient") OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 拉取远程文件（不做变更检测，用于首次 URL 导入）
     */
    public RemoteFetchResult fetch(String url, String fallbackFileName) {
        return doFetch(url, null, null, null, fallbackFileName);
    }

    /**
     * 流式拉取远程文件并检测变更（用于定时刷新场景）
     */
    public RemoteFetchResult fetchIfChanged(String url, String lastEtag, String lastModified,
                                            String lastContentHash, String fallbackFileName) {
        return doFetch(url, lastEtag, lastModified, lastContentHash, fallbackFileName);
    }

    private RemoteFetchResult doFetch(String url, String lastEtag, String lastModified,
                                      String lastContentHash, String fallbackFileName) {
        if (!StringUtils.hasText(url)) {
            throw new ClientException("远程文件地址不能为空");
        }
        long maxBytes = maxFileSize.toBytes();
        String trimmedUrl = url.trim();

        HeadInfo head = tryHead(trimmedUrl);
        if (head != null) {
            checkSizeLimit(maxBytes, head.contentLength());
            String etag = trimOrNull(head.etag());
            String headLastModified = trimOrNull(head.lastModified());
            boolean etagMatch = StringUtils.hasText(etag) && etag.equals(trimOrNull(lastEtag));
            boolean modifiedMatch = StringUtils.hasText(headLastModified)
                    && headLastModified.equals(trimOrNull(lastModified));
            if (etagMatch || modifiedMatch) {
                return RemoteFetchResult.skipped("远程文件未变化", etag, headLastModified, lastContentHash);
            }
        }

        Path tempFile = null;
        try {
            Request request = new Request.Builder().url(trimmedUrl).get().build();
            Response response = httpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                response.close();
                throw new ClientException("远程文件拉取失败: HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                response.close();
                throw new ClientException("远程文件内容为空");
            }

            long contentLength = body.contentLength();
            checkSizeLimit(maxBytes, contentLength);
            String contentType = response.header("Content-Type");
            String responseEtag = trimOrNull(response.header("ETag"));
            String responseLastModified = trimOrNull(response.header("Last-Modified"));
            String fileName = resolveFileName(response.header("Content-Disposition"), trimmedUrl, fallbackFileName);

            tempFile = Files.createTempFile("knowledge-schedule-", ".tmp");
            CopyResult copyResult;
            try (InputStream in = body.byteStream()) {
                copyResult = copyWithLimitAndDigest(in, tempFile, maxBytes);
            } finally {
                response.close();
            }

            if (copyResult.size() == 0) {
                deleteTempFileQuietly(tempFile);
                throw new ClientException("远程文件内容为空");
            }

            String hash = copyResult.sha256Hex;
            String etag = firstHasText(responseEtag, head == null ? null : trimOrNull(head.etag()));
            String fetchLastModified = firstHasText(responseLastModified,
                    head == null ? null : trimOrNull(head.lastModified()));

            if (StringUtils.hasText(hash) && hash.equals(trimOrNull(lastContentHash))) {
                deleteTempFileQuietly(tempFile);
                return RemoteFetchResult.skipped("内容哈希未变化", etag, fetchLastModified, hash);
            }

            return RemoteFetchResult.changed(tempFile, copyResult.size, contentType, fileName,
                    hash, etag, fetchLastModified);
        } catch (IOException e) {
            deleteTempFileQuietly(tempFile);
            throw new ServiceException("远程文件拉取失败: " + e.getMessage());
        } catch (RuntimeException e) {
            deleteTempFileQuietly(tempFile);
            throw e;
        }
    }

    private HeadInfo tryHead(String url) {
        try {
            Request request = new Request.Builder().url(url).head().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }
                return new HeadInfo(
                        response.header("ETag"),
                        response.header("Last-Modified"),
                        parseContentLength(response.header("Content-Length"))
                );
            }
        } catch (Exception e) {
            log.debug("HEAD 探测失败，降级为直接下载: url={}, reason={}", url, e.getMessage());
            return null;
        }
    }

    private CopyResult copyWithLimitAndDigest(InputStream in, Path target, long maxBytes) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new ClientException("远程文件超过大小限制: " + maxFileSize);
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        return new CopyResult(total, hexEncode(digest.digest()));
    }

    private void checkSizeLimit(long maxBytes, long contentLength) {
        if (contentLength > 0 && contentLength > maxBytes) {
            throw new ClientException("远程文件超过大小限制: " + maxFileSize);
        }
    }

    private static String resolveFileName(String contentDisposition, String url, String fallbackFileName) {
        if (StringUtils.hasText(contentDisposition)) {
            String lower = contentDisposition.toLowerCase();
            int idx = lower.indexOf("filename=");
            if (idx >= 0) {
                String name = contentDisposition.substring(idx + "filename=".length()).trim();
                if (name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2) {
                    name = name.substring(1, name.length() - 1);
                }
                int semi = name.indexOf(';');
                if (semi >= 0) {
                    name = name.substring(0, semi);
                }
                name = URLDecoder.decode(name.trim(), StandardCharsets.UTF_8);
                if (StringUtils.hasText(name)) {
                    return name;
                }
            }
        }
        try {
            String path = new java.net.URL(url).getPath();
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            if (StringUtils.hasText(name)) {
                return URLDecoder.decode(name, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // 忽略 URL 解析异常，回退到默认文件名
        }
        return fallbackFileName;
    }

    private static long parseContentLength(String value) {
        if (!StringUtils.hasText(value)) {
            return -1;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String firstHasText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static void deleteTempFileQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("删除临时文件失败: {}", path, e);
        }
    }

    private record HeadInfo(String etag, String lastModified, long contentLength) {
    }

    private record CopyResult(long size, String sha256Hex) {
    }

    /**
     * 拉取结果（AutoCloseable，关闭时清理临时文件）
     */
    public static final class RemoteFetchResult implements AutoCloseable {

        private final boolean changed;
        private final String message;
        private final Path tempFile;
        private final long size;
        private final String contentType;
        private final String fileName;
        private final String contentHash;
        private final String etag;
        private final String lastModified;

        private RemoteFetchResult(boolean changed, String message, Path tempFile, long size,
                                  String contentType, String fileName, String contentHash,
                                  String etag, String lastModified) {
            this.changed = changed;
            this.message = message;
            this.tempFile = tempFile;
            this.size = size;
            this.contentType = contentType;
            this.fileName = fileName;
            this.contentHash = contentHash;
            this.etag = etag;
            this.lastModified = lastModified;
        }

        static RemoteFetchResult skipped(String message, String etag, String lastModified, String contentHash) {
            return new RemoteFetchResult(false, message, null, 0, null, null, contentHash, etag, lastModified);
        }

        static RemoteFetchResult changed(Path tempFile, long size, String contentType, String fileName,
                                         String contentHash, String etag, String lastModified) {
            return new RemoteFetchResult(true, "远程文件已变化", tempFile, size, contentType,
                    fileName, contentHash, etag, lastModified);
        }

        public boolean changed() {
            return changed;
        }

        public String message() {
            return message;
        }

        public Path tempFile() {
            return tempFile;
        }

        public long size() {
            return size;
        }

        public String contentType() {
            return contentType;
        }

        public String fileName() {
            return fileName;
        }

        public String contentHash() {
            return contentHash;
        }

        public String etag() {
            return etag;
        }

        public String lastModified() {
            return lastModified;
        }

        @Override
        public void close() {
            deleteTempFileQuietly(tempFile);
        }
    }
}
