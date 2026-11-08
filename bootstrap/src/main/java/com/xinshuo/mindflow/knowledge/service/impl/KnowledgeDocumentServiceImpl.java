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
import com.xinshuo.mindflow.ingestion.domain.context.DocumentSource;
import com.xinshuo.mindflow.ingestion.domain.context.IngestionContext;
import com.xinshuo.mindflow.ingestion.domain.enums.IngestionStatus;
import com.xinshuo.mindflow.ingestion.domain.pipeline.PipelineDefinition;
import com.xinshuo.mindflow.ingestion.engine.IngestionEngine;
import com.xinshuo.mindflow.ingestion.service.IngestionPipelineService;
import com.xinshuo.mindflow.ingestion.util.MimeTypeDetector;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeChunkCreateRequest;
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
import com.xinshuo.mindflow.knowledge.handler.RemoteFileFetcher;
import com.xinshuo.mindflow.knowledge.service.KnowledgeChunkService;
import com.xinshuo.mindflow.knowledge.service.KnowledgeDocumentScheduleService;
import com.xinshuo.mindflow.knowledge.service.KnowledgeDocumentService;
import com.xinshuo.mindflow.rag.core.vector.VectorSpaceId;
import com.xinshuo.mindflow.rag.core.vector.VectorStoreService;
import com.xinshuo.mindflow.rag.dto.StoredFileDTO;
import com.xinshuo.mindflow.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;

import java.io.InputStream;
import java.nio.file.Files;
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
    private final KnowledgeChunkService knowledgeChunkService;
    private final VectorStoreService vectorStoreService;
    private final TransactionOperations transactionOperations;
    private final KnowledgeDocumentScheduleService scheduleService;
    private final RemoteFileFetcher remoteFileFetcher;
    private final IngestionEngine ingestionEngine;
    private final IngestionPipelineService ingestionPipelineService;

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

        if (!fileStorageService.bucketExists(kbDO.getCollectionName())) {
            fileStorageService.createBucket(kbDO.getCollectionName());
        }

        StoredFileDTO stored;
        if (SourceType.URL == sourceType) {
            // URL 来源：拉取远程文件后落库，后续可被定时任务刷新
            String sourceLocation = StrUtil.trimToNull(requestParam.getSourceLocation());
            if (sourceLocation == null) {
                throw new ClientException("远程文件地址不能为空");
            }
            try (RemoteFileFetcher.RemoteFetchResult fetchResult =
                         remoteFileFetcher.fetch(sourceLocation, "remote-document")) {
                if (fetchResult.tempFile() == null) {
                    throw new ClientException("远程文件内容未获取到");
                }
                try (InputStream in = Files.newInputStream(fetchResult.tempFile())) {
                    stored = fileStorageService.upload(
                            kbDO.getCollectionName(),
                            in,
                            fetchResult.size(),
                            fetchResult.fileName(),
                            fetchResult.contentType());
                }
            } catch (ClientException e) {
                throw e;
            } catch (Exception e) {
                throw new ClientException("远程文件拉取失败: " + e.getMessage());
            }
        } else {
            stored = fileStorageService.upload(kbDO.getCollectionName(), file);
        }

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
        // URL 来源且开启定时：同步创建定时刷新任务
        scheduleService.upsertSchedule(documentDO);

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
        chunkDocument(doc);
    }

    /**
     * 按给定文档快照执行分块
     * <p>
     * 定时刷新场景会传入带有新 fileUrl 的运行时文档，避免先落库文件元数据再接分块，
     * 分块成功后才由调用方切换文件元数据（与 ragent chunkDocument 语义一致）。
     *
     * @param doc 文档快照（id 必填）
     */
    public void chunkDocument(KnowledgeDocumentDO doc) {
        String docId = doc.getId();
        // process_mode=pipeline 时走可编排的摄取流水线
        if (ProcessMode.PIPELINE == ProcessMode.fromValue(doc.getProcessMode())
                && StringUtils.hasText(doc.getPipelineId())) {
            runPipelineProcess(doc);
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

            // 阶段4: 持久化——写入 t_knowledge_chunk + t_knowledge_vector
            String collectionName = kb.getCollectionName();
            List<KnowledgeChunkCreateRequest> chunkReqs = new ArrayList<>(chunks.size());
            for (VectorChunk vc : chunks) {
                KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
                req.setChunkId(vc.getChunkId());
                req.setIndex(vc.getIndex());
                req.setContent(vc.getContent());
                chunkReqs.add(req);
            }
            transactionOperations.executeWithoutResult(status -> {
                knowledgeChunkService.deleteByDocId(docId);
                knowledgeChunkService.batchCreate(docId, chunkReqs);
                vectorStoreService.deleteDocumentVectors(collectionName, docId);
                vectorStoreService.indexDocumentChunks(collectionName, docId, chunks);
                documentMapper.update(
                        new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                                .set(KnowledgeDocumentDO::getChunkCount, chunks.size())
                                .set(KnowledgeDocumentDO::getStatus, DocumentStatus.SUCCESS.getCode())
                                .set(KnowledgeDocumentDO::getUpdatedBy, UserContext.getUsername())
                                .eq(KnowledgeDocumentDO::getId, docId)
                );
            });
            log.info("文档分块+持久化完成, docId={}, chunks={}", docId, chunks.size());
        } catch (Exception e) {
            log.error("文档分块失败, docId={}", docId, e);
            documentMapper.update(
                    new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                            .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                            .eq(KnowledgeDocumentDO::getId, docId)
            );
        }
    }

    /**
     * 执行可编排的摄取流水线（process_mode=pipeline）
     * <p>
     * 流水线内 ChunkerNode 已生成 embedding；IndexerNode 以 skipIndexerWrite=true 只做校验，
     * 分块与向量由这里在同一事务内统一落库。
     */
    private void runPipelineProcess(KnowledgeDocumentDO doc) {
        String docId = doc.getId();
        documentMapper.update(
                new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .ne(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        );

        try {
            KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(doc.getKbId());
            Assert.notNull(kb, () -> new ClientException("知识库不存在"));

            byte[] fileBytes;
            try (InputStream is = fileStorageService.openStream(doc.getFileUrl())) {
                fileBytes = is.readAllBytes();
            }
            String mime = MimeTypeDetector.detect(fileBytes, doc.getDocName());

            PipelineDefinition pipeline = ingestionPipelineService.getDefinition(doc.getPipelineId());
            IngestionContext context = IngestionContext.builder()
                    .taskId(docId)
                    .pipelineId(doc.getPipelineId())
                    .source(DocumentSource.builder()
                            .type(com.xinshuo.mindflow.ingestion.domain.enums.SourceType.FILE)
                            .location(doc.getFileUrl())
                            .fileName(doc.getDocName())
                            .build())
                    .rawBytes(fileBytes)
                    .mimeType(mime)
                    .vectorSpaceId(VectorSpaceId.builder().logicalName(kb.getCollectionName()).build())
                    .skipIndexerWrite(true)
                    .logs(new ArrayList<>())
                    .build();

            IngestionContext result = ingestionEngine.execute(pipeline, context);
            if (result.getStatus() != IngestionStatus.COMPLETED
                    || result.getChunks() == null || result.getChunks().isEmpty()) {
                String reason = result.getError() == null
                        ? String.valueOf(result.getStatus())
                        : result.getError().getMessage();
                throw new ClientException("摄取流水线未产出分块: " + reason);
            }

            List<VectorChunk> chunks = result.getChunks();
            List<KnowledgeChunkCreateRequest> chunkReqs = new ArrayList<>(chunks.size());
            for (VectorChunk vc : chunks) {
                KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
                req.setChunkId(vc.getChunkId());
                req.setIndex(vc.getIndex());
                req.setContent(vc.getContent());
                chunkReqs.add(req);
            }

            String collectionName = kb.getCollectionName();
            transactionOperations.executeWithoutResult(status -> {
                knowledgeChunkService.deleteByDocId(docId);
                knowledgeChunkService.batchCreate(docId, chunkReqs, false);
                vectorStoreService.deleteDocumentVectors(collectionName, docId);
                vectorStoreService.indexDocumentChunks(collectionName, docId, chunks);
                documentMapper.update(
                        new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                                .set(KnowledgeDocumentDO::getChunkCount, chunks.size())
                                .set(KnowledgeDocumentDO::getStatus, DocumentStatus.SUCCESS.getCode())
                                .set(KnowledgeDocumentDO::getUpdatedBy, UserContext.getUsername())
                                .eq(KnowledgeDocumentDO::getId, docId)
                );
            });
            log.info("流水线分块+持久化完成, docId={}, chunks={}", docId, chunks.size());
        } catch (Exception e) {
            log.error("流水线分块失败, docId={}", docId, e);
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

        // 删除关联的定时任务与执行记录
        scheduleService.deleteByDocId(docId);

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

        if (requestParam.getSourceLocation() != null) {
            documentDO.setSourceLocation(StrUtil.trimToNull(requestParam.getSourceLocation()));
            updateWrapper.set(KnowledgeDocumentDO::getSourceLocation, documentDO.getSourceLocation());
        }
        if (requestParam.getScheduleEnabled() != null) {
            documentDO.setScheduleEnabled(requestParam.getScheduleEnabled());
            updateWrapper.set(KnowledgeDocumentDO::getScheduleEnabled, requestParam.getScheduleEnabled());
        }
        if (requestParam.getScheduleCron() != null) {
            documentDO.setScheduleCron(StrUtil.trimToNull(requestParam.getScheduleCron()));
            updateWrapper.set(KnowledgeDocumentDO::getScheduleCron, documentDO.getScheduleCron());
        }

        documentMapper.update(updateWrapper);
        // 定时配置变更后同步调度表（URL 来源才会真正落任务）
        scheduleService.upsertSchedule(documentDO);
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
        // 文档启用/禁用时同步已存在的定时任务（禁用则关闭调度）
        scheduleService.syncScheduleIfExists(documentDO);

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
