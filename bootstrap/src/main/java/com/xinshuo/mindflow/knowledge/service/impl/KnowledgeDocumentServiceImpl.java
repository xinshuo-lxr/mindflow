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

package com.xinshuo.mindflow.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinshuo.mindflow.core.chunk.ChunkEmbeddingService;
import com.xinshuo.mindflow.core.chunk.ChunkingMode;
import com.xinshuo.mindflow.core.chunk.StructuredChunkingService;
import com.xinshuo.mindflow.core.chunk.VectorChunk;
import com.xinshuo.mindflow.core.parser.BlockTextRenderer;
import com.xinshuo.mindflow.core.parser.DocumentParser;
import com.xinshuo.mindflow.core.parser.DocumentParserSelector;
import com.xinshuo.mindflow.core.parser.model.ParsedDocument;
import com.xinshuo.mindflow.framework.context.UserContext;
import com.xinshuo.mindflow.framework.exception.ClientException;
import com.xinshuo.mindflow.ingestion.util.MimeTypeDetector;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.xinshuo.mindflow.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.xinshuo.mindflow.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.xinshuo.mindflow.knowledge.controller.vo.KnowledgeDocumentVO;
import com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeBaseDO;
import com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeDocumentDO;
import com.xinshuo.mindflow.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.xinshuo.mindflow.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.xinshuo.mindflow.knowledge.enums.DocumentStatus;
import com.xinshuo.mindflow.knowledge.enums.ProcessMode;
import com.xinshuo.mindflow.knowledge.enums.SourceType;
import com.xinshuo.mindflow.knowledge.service.KnowledgeDocumentService;
import com.xinshuo.mindflow.rag.dto.StoredFileDTO;
import com.xinshuo.mindflow.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库文档服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final FileStorageService fileStorageService;
    private final DocumentParserSelector parserSelector;
    private final StructuredChunkingService structuredChunkingService;
    private final ChunkEmbeddingService chunkEmbeddingService;

    @Override
    public KnowledgeDocumentVO upload(String kbId, KnowledgeDocumentUploadRequest requestParam, MultipartFile file) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));

        if (!StringUtils.hasText(requestParam.getSourceType())) {
            requestParam.setSourceType(SourceType.FILE.getValue());
        }
        SourceType sourceType = SourceType.normalize(requestParam.getSourceType());

        if (SourceType.FILE == sourceType) {
            Assert.notNull(file, () -> new ClientException("上传文件不能为空"));
        }
        if (SourceType.URL == sourceType) {
            throw new ClientException("暂不支持 URL 来源，请使用 file 上传");
        }

        if (!fileStorageService.bucketExists(kbDO.getCollectionName())) {
            fileStorageService.createBucket(kbDO.getCollectionName());
        }

        StoredFileDTO stored = fileStorageService.upload(kbDO.getCollectionName(), file);

        ProcessMode processMode = resolveProcessMode(requestParam);

        KnowledgeDocumentDO documentDO = KnowledgeDocumentDO.builder()
                .kbId(kbId)
                .docName(stored.getOriginalFilename())
                .enabled(1)
                .chunkCount(0)
                .fileUrl(stored.getUrl())
                .fileType(stored.getDetectedType())
                .fileSize(stored.getSize())
                .status(DocumentStatus.PENDING.getCode())
                .sourceType(sourceType.getValue())
                .sourceLocation(SourceType.URL == sourceType ? StrUtil.trimToNull(requestParam.getSourceLocation()) : null)
                .scheduleEnabled(Boolean.TRUE.equals(requestParam.getScheduleEnabled()) ? 1 : 0)
                .scheduleCron(Boolean.TRUE.equals(requestParam.getScheduleEnabled()) ? StrUtil.trimToNull(requestParam.getScheduleCron()) : null)
                .processMode(processMode.getValue())
                .chunkStrategy(ProcessMode.CHUNK == processMode && StringUtils.hasText(requestParam.getChunkStrategy())
                        ? requestParam.getChunkStrategy() : null)
                .chunkConfig(ProcessMode.CHUNK == processMode ? requestParam.getChunkConfig() : null)
                .pipelineId(ProcessMode.PIPELINE == processMode ? requestParam.getPipelineId() : null)
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        documentMapper.insert(documentDO);

        log.info("文档上传成功, kbId={}, docId={}, docName={}, fileType={}",
                kbId, documentDO.getId(), documentDO.getDocName(), documentDO.getFileType());
        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    @Override
    public void startChunk(String docId) {
        // 第 22 步：接入 MQ 事务消息
        executeChunk(docId);
    }

    @Override
    public void executeChunk(String docId) {
        KnowledgeDocumentDO doc = documentMapper.selectById(docId);
        if (doc == null) {
            log.warn("文档不存在，跳过分块, docId={}", docId);
            return;
        }
        // 状态流转: pending → running
        documentMapper.update(
                new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .ne(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        );

        try {
            KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(doc.getKbId());
            ChunkingMode mode = ChunkingMode.fromValue(doc.getChunkStrategy());

            // 阶段1: 提取——按 MIME 路由解析器
            byte[] fileBytes;
            try (InputStream is = fileStorageService.openStream(doc.getFileUrl())) {
                fileBytes = is.readAllBytes();
            }
            String mime = MimeTypeDetector.detect(fileBytes, doc.getDocName());
            DocumentParser parser = parserSelector.selectByMimeType(mime);
            if (parser == null) {
                throw new ClientException("未找到 MIME [" + mime + "] 对应的解析器");
            }
            ParsedDocument parsed = parser.parseStructured(fileBytes, mime,
                    Map.of("sourceFile", doc.getDocName()));
            String fallbackText = BlockTextRenderer.render(parsed.blocks());

            // 阶段2: 分块
            List<VectorChunk> chunks = structuredChunkingService.chunk(
                    parsed.blocks(), fallbackText, mode,
                    mode.createOptions(Map.of()), null);

            // 阶段3: 嵌入
            chunkEmbeddingService.embed(chunks, kb.getEmbeddingModel());

            // 阶段4: 持久化——第 9 步接入 KnowledgeChunkService + VectorStoreService
            log.info("文档分块完成, docId={}, chunks={}", docId, chunks.size());
            documentMapper.update(
                    new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                            .set(KnowledgeDocumentDO::getChunkCount, chunks.size())
                            .set(KnowledgeDocumentDO::getStatus, DocumentStatus.SUCCESS.getCode())
                            .eq(KnowledgeDocumentDO::getId, docId)
            );
        } catch (Exception e) {
            log.error("文档分块失败, docId={}", docId, e);
            documentMapper.update(
                    new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                            .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                            .eq(KnowledgeDocumentDO::getId, docId)
            );
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 禁止在文档分块运行时删除
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法删除");
        }

        documentDO.setDeleted(1);
        documentDO.setUpdatedBy(UserContext.getUsername());
        documentMapper.deleteById(documentDO);

        // 清理存储文件
        deleteStoredFileQuietly(documentDO);

        log.info("文档删除成功, docId={}, docName={}", docId, documentDO.getDocName());
    }

    @Override
    public KnowledgeDocumentVO get(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String docId, KnowledgeDocumentUpdateRequest requestParam) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 禁止在文档分块运行时修改
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法修改");
        }

        String docName = requestParam == null ? null : requestParam.getDocName();
        if (!StringUtils.hasText(docName)) {
            throw new ClientException("文档名称不能为空");
        }

        LambdaUpdateWrapper<KnowledgeDocumentDO> updateWrapper = Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, documentDO.getId())
                .set(KnowledgeDocumentDO::getDocName, docName.trim())
                .set(KnowledgeDocumentDO::getUpdatedBy, UserContext.getUsername());

        // 后续实现：补充 processMode / chunkStrategy / chunkConfig 更新逻辑
        // 后续实现：补充定时调度字段更新

        documentMapper.update(updateWrapper);
        log.info("文档更新成功, docId={}, newName={}", docId, docName);
    }

    @Override
    public IPage<KnowledgeDocumentVO> page(String kbId, KnowledgeDocumentPageRequest requestParam) {
        Page<KnowledgeDocumentDO> pageParam = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        LambdaQueryWrapper<KnowledgeDocumentDO> queryWrapper = Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getKbId, kbId)
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(requestParam.getKeyword() != null && !requestParam.getKeyword().isBlank(),
                        KnowledgeDocumentDO::getDocName, requestParam.getKeyword())
                .eq(requestParam.getStatus() != null && !requestParam.getStatus().isBlank(),
                        KnowledgeDocumentDO::getStatus, requestParam.getStatus())
                .orderByDesc(KnowledgeDocumentDO::getCreateTime);

        IPage<KnowledgeDocumentVO> result = documentMapper.selectPage(pageParam, queryWrapper)
                .convert(each -> BeanUtil.toBean(each, KnowledgeDocumentVO.class));

        return result;
    }

    @Override
    public List<KnowledgeDocumentSearchVO> search(String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }

        int size = Math.min(Math.max(limit, 1), 20);
        Page<KnowledgeDocumentDO> mpPage = new Page<>(1, size);
        LambdaQueryWrapper<KnowledgeDocumentDO> qw = new LambdaQueryWrapper<KnowledgeDocumentDO>()
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(KnowledgeDocumentDO::getDocName, keyword)
                .orderByDesc(KnowledgeDocumentDO::getUpdateTime);

        IPage<KnowledgeDocumentDO> result = documentMapper.selectPage(mpPage, qw);
        List<KnowledgeDocumentSearchVO> records = result.getRecords().stream()
                .map(each -> BeanUtil.toBean(each, KnowledgeDocumentSearchVO.class))
                .toList();
        if (records.isEmpty()) {
            return records;
        }

        Set<String> kbIds = new HashSet<>();
        for (KnowledgeDocumentSearchVO record : records) {
            if (record.getKbId() != null) {
                kbIds.add(record.getKbId());
            }
        }
        if (kbIds.isEmpty()) {
            return records;
        }

        List<KnowledgeBaseDO> bases = knowledgeBaseMapper.selectByIds(kbIds);
        Map<String, String> nameMap = new HashMap<>();
        if (bases != null) {
            for (KnowledgeBaseDO base : bases) {
                nameMap.put(base.getId(), base.getName());
            }
        }
        for (KnowledgeDocumentSearchVO record : records) {
            record.setKbName(nameMap.get(record.getKbId()));
        }
        return records;
    }

    @Override
    public void enable(String docId, boolean enabled) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 禁止在文档分块运行时修改
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法修改");
        }

        int targetEnabled = enabled ? 1 : 0;
        if (documentDO.getEnabled() != null && documentDO.getEnabled() == targetEnabled) {
            return;
        }

        documentDO.setEnabled(targetEnabled);
        documentDO.setUpdatedBy(UserContext.getUsername());
        documentMapper.updateById(documentDO);

        // 第 9 步：补充启用时重建向量、禁用时清理向量
        log.info("文档{}成功, docId={}", enabled ? "启用" : "禁用", docId);
    }

    @Override
    public IPage<KnowledgeDocumentChunkLogVO> getChunkLogs(String docId, Page<KnowledgeDocumentChunkLogVO> page) {
        // 第 9 步实现：查询 chunk log 表
        throw new UnsupportedOperationException("第 9 步实现");
    }

    @Override
    public String preview(String docId) {
        // 第 9 步实现：读取 markdown 文件内容
        throw new UnsupportedOperationException("第 9 步实现");
    }

    private ProcessMode resolveProcessMode(KnowledgeDocumentUploadRequest request) {
        if (!StringUtils.hasText(request.getProcessMode())) {
            return ProcessMode.CHUNK;
        }
        return ProcessMode.normalize(request.getProcessMode());
    }

    private void deleteStoredFileQuietly(KnowledgeDocumentDO documentDO) {
        if (documentDO == null || !StringUtils.hasText(documentDO.getFileUrl())) {
            return;
        }
        try {
            fileStorageService.deleteByUrl(documentDO.getFileUrl());
        } catch (Exception e) {
            log.warn("删除文档存储文件失败, docId={}, fileUrl={}", documentDO.getId(), documentDO.getFileUrl(), e);
        }
    }
}
