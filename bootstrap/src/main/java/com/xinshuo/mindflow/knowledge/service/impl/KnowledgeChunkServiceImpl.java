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
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinshuo.mindflow.core.chunk.VectorChunk;
import com.xinshuo.mindflow.framework.context.UserContext;
import com.xinshuo.mindflow.framework.exception.ClientException;
import com.xinshuo.mindflow.framework.exception.ServiceException;
import com.xinshuo.mindflow.infra.embedding.EmbeddingService;
import com.xinshuo.mindflow.infra.token.TokenCounterService;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.xinshuo.mindflow.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.xinshuo.mindflow.knowledge.controller.vo.KnowledgeChunkVO;
import com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeBaseDO;
import com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeChunkDO;
import com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeDocumentDO;
import com.xinshuo.mindflow.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.xinshuo.mindflow.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.xinshuo.mindflow.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.xinshuo.mindflow.knowledge.enums.DocumentStatus;
import com.xinshuo.mindflow.knowledge.service.KnowledgeChunkService;
import com.xinshuo.mindflow.rag.core.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChunkServiceImpl implements KnowledgeChunkService {

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final EmbeddingService embeddingService;
    private final TokenCounterService tokenCounterService;
    private final VectorStoreService vectorStoreService;
    private final TransactionOperations transactionOperations;

    @Override
    public IPage<KnowledgeChunkVO> pageQuery(String docId, KnowledgeChunkPageRequest requestParam) {
        KnowledgeDocumentDO doc = documentMapper.selectById(docId);
        Assert.notNull(doc, () -> new ClientException("文档不存在"));

        LambdaQueryWrapper<KnowledgeChunkDO> qw = new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getDocId, docId)
                .eq(requestParam.getEnabled() != null, KnowledgeChunkDO::getEnabled, requestParam.getEnabled())
                .orderByAsc(KnowledgeChunkDO::getChunkIndex);

        Page<KnowledgeChunkDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        return chunkMapper.selectPage(page, qw).convert(each -> BeanUtil.toBean(each, KnowledgeChunkVO.class));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeChunkVO create(String docId, KnowledgeChunkCreateRequest requestParam) {
        KnowledgeDocumentDO doc = documentMapper.selectById(docId);
        Assert.notNull(doc, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(doc.getStatus()))
            throw new ClientException("文档正在分块处理中，暂不支持新增 Chunk");
        if (!Integer.valueOf(1).equals(doc.getEnabled()))
            throw new ClientException("文档未启用，暂不支持新增 Chunk");

        String content = requestParam.getContent();
        Assert.notBlank(content, () -> new ClientException("Chunk 内容不能为空"));

        KnowledgeChunkDO latest = chunkMapper.selectOne(Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                .eq(KnowledgeChunkDO::getDocId, docId).orderByDesc(KnowledgeChunkDO::getChunkIndex).last("LIMIT 1"));
        int chunkIndex = requestParam.getIndex() != null ? requestParam.getIndex() : (latest != null ? latest.getChunkIndex() + 1 : 0);

        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(doc.getKbId());
        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                .id(requestParam.getChunkId())
                .kbId(doc.getKbId()).docId(docId).chunkIndex(chunkIndex)
                .content(content).contentHash(SecureUtil.sha256(content))
                .charCount(content.length()).tokenCount(resolveTokenCount(content))
                .enabled(1).createdBy(UserContext.getUsername()).updatedBy(UserContext.getUsername()).build();
        chunkMapper.insert(chunkDO);

        documentMapper.update(Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, docId).setSql("chunk_count = chunk_count + 1"));

        syncChunkToVector(kb.getCollectionName(), docId, chunkDO, kb.getEmbeddingModel());
        return BeanUtil.toBean(chunkDO, KnowledgeChunkVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchCreate(String docId, List<KnowledgeChunkCreateRequest> requestParams) {
        batchCreate(docId, requestParams, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchCreate(String docId, List<KnowledgeChunkCreateRequest> requestParams, boolean writeVector) {
        if (CollUtil.isEmpty(requestParams)) return;
        KnowledgeDocumentDO doc = documentMapper.selectById(docId);
        Assert.notNull(doc, () -> new ClientException("文档不存在"));

        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(doc.getKbId());
        String username = UserContext.getUsername();
        List<KnowledgeChunkDO> chunkDOList = new ArrayList<>(requestParams.size());
        int nextIndex = 0;
        for (KnowledgeChunkCreateRequest req : requestParams) {
            String content = req.getContent();
            Assert.notBlank(content, () -> new ClientException("Chunk 内容不能为空"));
            Integer idx = req.getIndex();
            if (idx == null) idx = nextIndex++;
            else if (idx >= nextIndex) nextIndex = idx + 1;
            String chunkId = StringUtils.hasText(req.getChunkId()) ? req.getChunkId() : IdUtil.getSnowflakeNextIdStr();
            chunkDOList.add(KnowledgeChunkDO.builder()
                    .id(chunkId).kbId(doc.getKbId()).docId(docId).chunkIndex(idx)
                    .content(content).contentHash(SecureUtil.sha256(content))
                    .charCount(content.length()).tokenCount(resolveTokenCount(content))
                    .enabled(1).createdBy(username).updatedBy(username).build());
        }
        chunkMapper.insert(chunkDOList);
        documentMapper.update(Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, docId).setSql("chunk_count = chunk_count + " + chunkDOList.size()));

        if (writeVector) {
            List<VectorChunk> vecChunks = chunkDOList.stream().map(c -> VectorChunk.builder()
                    .chunkId(c.getId()).content(c.getContent()).index(c.getChunkIndex()).build()).toList();
            if (CollUtil.isNotEmpty(vecChunks)) {
                attachEmbeddings(vecChunks, kb.getEmbeddingModel());
                vectorStoreService.indexDocumentChunks(kb.getCollectionName(), docId, vecChunks);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String docId, String chunkId, KnowledgeChunkUpdateRequest requestParam) {
        KnowledgeDocumentDO doc = documentMapper.selectById(docId);
        Assert.notNull(doc, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(doc.getStatus()))
            throw new ClientException("文档正在分块处理中");

        KnowledgeChunkDO chunk = chunkMapper.selectById(chunkId);
        Assert.notNull(chunk, () -> new ClientException("Chunk 不存在"));
        Assert.isTrue(chunk.getDocId().equals(docId), () -> new ClientException("Chunk 不属于该文档"));

        String newContent = requestParam.getContent();
        Assert.notBlank(newContent, () -> new ClientException("Chunk 内容不能为空"));
        if (newContent.equals(chunk.getContent())) return;

        chunk.setContent(newContent);
        chunk.setContentHash(SecureUtil.sha256(newContent));
        chunk.setCharCount(newContent.length());
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(doc.getKbId());
        chunk.setTokenCount(resolveTokenCount(newContent));
        chunk.setUpdatedBy(UserContext.getUsername());
        chunkMapper.updateById(chunk);

        vectorStoreService.updateChunk(kb.getCollectionName(), docId,
                VectorChunk.builder().chunkId(chunkId).content(newContent).index(chunk.getChunkIndex())
                        .embedding(toArray(embedContent(newContent, kb.getEmbeddingModel()))).build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId, String chunkId) {
        KnowledgeDocumentDO doc = documentMapper.selectById(docId);
        Assert.notNull(doc);
        if (DocumentStatus.RUNNING.getCode().equals(doc.getStatus()))
            throw new ClientException("文档正在分块处理中");
        KnowledgeChunkDO chunk = chunkMapper.selectById(chunkId);
        Assert.notNull(chunk);
        Assert.isTrue(chunk.getDocId().equals(docId));
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(doc.getKbId());
        Assert.notNull(kb);

        chunkMapper.deleteById(chunkId);
        documentMapper.update(Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, docId).setSql("chunk_count = CASE WHEN chunk_count > 0 THEN chunk_count - 1 ELSE 0 END"));
        deleteChunkFromVector(kb.getCollectionName(), chunkId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enableChunk(String docId, String chunkId, boolean enabled) {
        KnowledgeDocumentDO doc = documentMapper.selectById(docId);
        Assert.notNull(doc);
        if (DocumentStatus.RUNNING.getCode().equals(doc.getStatus()))
            throw new ClientException("文档正在分块处理中");
        if (enabled && !Integer.valueOf(1).equals(doc.getEnabled()))
            throw new ClientException("文档未启用，无法启用Chunk");

        KnowledgeChunkDO chunk = chunkMapper.selectById(chunkId);
        Assert.notNull(chunk);
        Assert.isTrue(chunk.getDocId().equals(docId));
        int target = enabled ? 1 : 0;
        if (chunk.getEnabled().equals(target)) return;

        chunk.setEnabled(target);
        chunk.setUpdatedBy(UserContext.getUsername());
        chunkMapper.updateById(chunk);

        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(doc.getKbId());
        if (enabled) syncChunkToVector(kb.getCollectionName(), docId, chunk, kb.getEmbeddingModel());
        else deleteChunkFromVector(kb.getCollectionName(), chunkId);
    }

    @Override
    public void batchToggleEnabled(String docId, KnowledgeChunkBatchRequest requestParam, boolean enabled) {
        if (requestParam == null || CollUtil.isEmpty(requestParam.getChunkIds()))
            throw new ClientException("请指定需要操作的 Chunk");
        List<String> ids = requestParam.getChunkIds();
        if (ids.size() > 500) throw new ClientException("单次批量操作不能超过 500");

        KnowledgeDocumentDO doc = documentMapper.selectById(docId);
        Assert.notNull(doc);
        if (DocumentStatus.RUNNING.getCode().equals(doc.getStatus()))
            throw new ClientException("文档正在分块处理中");

        List<KnowledgeChunkDO> found = chunkMapper.selectByIds(ids);
        if (found.size() != ids.size()) throw new ClientException("存在无效的 Chunk ID");
        found.forEach(c -> { if (!c.getDocId().equals(docId)) throw new ClientException("Chunk 不属于该文档"); });

        List<String> targetIds = found.stream().map(KnowledgeChunkDO::getId).toList();
        if (CollUtil.isEmpty(targetIds)) return;

        int enabledVal = enabled ? 1 : 0;
        List<KnowledgeChunkDO> needUpdate = chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunkDO>()
                .in(KnowledgeChunkDO::getId, targetIds).ne(KnowledgeChunkDO::getEnabled, enabledVal));
        List<String> needIds = needUpdate.stream().map(KnowledgeChunkDO::getId).toList();
        if (CollUtil.isEmpty(needIds))
            throw new ClientException(enabled ? "所有 Chunk 已全部启用" : "所有 Chunk 已全部禁用");

        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(doc.getKbId());
        if (enabled) {
            List<VectorChunk> vecChunks = needUpdate.stream().map(c -> VectorChunk.builder()
                    .chunkId(c.getId()).content(c.getContent()).index(c.getChunkIndex()).build()).toList();
            attachEmbeddings(vecChunks, kb.getEmbeddingModel());
            transactionOperations.executeWithoutResult(status -> {
                chunkMapper.update(Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                        .in(KnowledgeChunkDO::getId, needIds).set(KnowledgeChunkDO::getEnabled, 1)
                        .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername()));
                vectorStoreService.indexDocumentChunks(kb.getCollectionName(), docId, vecChunks);
            });
        } else {
            transactionOperations.executeWithoutResult(status -> {
                chunkMapper.update(Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                        .in(KnowledgeChunkDO::getId, needIds).set(KnowledgeChunkDO::getEnabled, 0)
                        .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername()));
                vectorStoreService.deleteChunksByIds(kb.getCollectionName(), needIds);
            });
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEnabledByDocId(String docId, String kbId, boolean enabled) {
        int val = enabled ? 1 : 0;
        chunkMapper.update(Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                .eq(KnowledgeChunkDO::getDocId, docId)
                .set(KnowledgeChunkDO::getEnabled, val)
                .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername()));
    }

    @Override
    public List<KnowledgeChunkVO> listByDocId(String docId) {
        Assert.notNull(documentMapper.selectById(docId), () -> new ClientException("文档不存在"));
        return chunkMapper.selectList(Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                .eq(KnowledgeChunkDO::getDocId, docId).orderByAsc(KnowledgeChunkDO::getChunkIndex))
                .stream().map(each -> BeanUtil.toBean(each, KnowledgeChunkVO.class)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocId(String docId) {
        if (docId == null) return;
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkDO>().eq(KnowledgeChunkDO::getDocId, docId));
    }

    // ====== private helpers ======

    private void syncChunkToVector(String collection, String docId, KnowledgeChunkDO chunk, String model) {
        float[] vec = toArray(embedContent(chunk.getContent(), model));
        vectorStoreService.indexDocumentChunks(collection, docId,
                List.of(VectorChunk.builder().chunkId(chunk.getId()).content(chunk.getContent()).index(chunk.getChunkIndex()).embedding(vec).build()));
    }

    private void deleteChunkFromVector(String collection, String chunkId) {
        vectorStoreService.deleteChunkById(collection, chunkId);
    }

    private void attachEmbeddings(List<VectorChunk> chunks, String model) {
        if (CollUtil.isEmpty(chunks)) return;
        List<String> texts = chunks.stream().map(VectorChunk::getContent).toList();
        List<List<Float>> vectors = StrUtil.isBlank(model) ? embeddingService.embedBatch(texts) : embeddingService.embedBatch(texts, model);
        if (vectors == null || vectors.size() != chunks.size()) throw new ServiceException("向量结果数量不匹配");
        for (int i = 0; i < chunks.size(); i++) chunks.get(i).setEmbedding(toArray(vectors.get(i)));
    }

    private List<Float> embedContent(String content, String model) {
        return StrUtil.isBlank(model) ? embeddingService.embed(content) : embeddingService.embed(content, model);
    }

    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private Integer resolveTokenCount(String content) {
        return tokenCounterService.countTokens(content);
    }
}
