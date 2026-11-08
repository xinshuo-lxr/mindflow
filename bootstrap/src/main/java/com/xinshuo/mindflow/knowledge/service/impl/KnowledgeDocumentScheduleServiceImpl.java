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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xinshuo.mindflow.framework.exception.ClientException;
import com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeDocumentDO;
import com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;
import com.xinshuo.mindflow.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.xinshuo.mindflow.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.xinshuo.mindflow.knowledge.enums.SourceType;
import com.xinshuo.mindflow.knowledge.schedule.CronScheduleHelper;
import com.xinshuo.mindflow.knowledge.service.KnowledgeDocumentScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentScheduleServiceImpl implements KnowledgeDocumentScheduleService {

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final KnowledgeDocumentScheduleExecMapper scheduleExecMapper;

    @Value("${rag.knowledge.schedule.min-interval-seconds:60}")
    private long scheduleMinIntervalSeconds;

    @Override
    public void upsertSchedule(KnowledgeDocumentDO documentDO) {
        syncSchedule(documentDO, true);
    }

    @Override
    public void syncScheduleIfExists(KnowledgeDocumentDO documentDO) {
        syncSchedule(documentDO, false);
    }

    private void syncSchedule(KnowledgeDocumentDO documentDO, boolean allowCreate) {
        if (documentDO == null) {
            return;
        }
        if (documentDO.getId() == null || documentDO.getKbId() == null) {
            return;
        }
        if (!Objects.equals(SourceType.URL.getValue(), documentDO.getSourceType())) {
            return;
        }
        boolean docEnabled = documentDO.getEnabled() == null || documentDO.getEnabled() == 1;
        String cron = documentDO.getScheduleCron();
        boolean enabled = documentDO.getScheduleEnabled() != null && documentDO.getScheduleEnabled() == 1;
        if (!StringUtils.hasText(cron)) {
            enabled = false;
        }
        if (!docEnabled) {
            enabled = false;
        }

        Date nextRunTime = null;
        if (enabled) {
            try {
                if (CronScheduleHelper.isIntervalLessThan(cron, new Date(), scheduleMinIntervalSeconds)) {
                    throw new ClientException("定时周期不能小于 " + scheduleMinIntervalSeconds + " 秒");
                }
                nextRunTime = CronScheduleHelper.nextRunTime(cron, new Date());
            } catch (IllegalArgumentException e) {
                throw new ClientException("定时表达式不合法");
            }
        }

        KnowledgeDocumentScheduleDO existing = scheduleMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentScheduleDO>()
                        .eq(KnowledgeDocumentScheduleDO::getDocId, documentDO.getId())
                        .last("LIMIT 1")
        );

        if (existing == null) {
            if (!allowCreate) {
                return;
            }
            KnowledgeDocumentScheduleDO schedule = KnowledgeDocumentScheduleDO.builder()
                    .docId(documentDO.getId())
                    .kbId(documentDO.getKbId())
                    .cronExpr(cron)
                    .enabled(enabled ? 1 : 0)
                    .nextRunTime(nextRunTime)
                    .build();
            scheduleMapper.insert(schedule);
        } else {
            scheduleMapper.update(
                    new LambdaUpdateWrapper<KnowledgeDocumentScheduleDO>()
                            .eq(KnowledgeDocumentScheduleDO::getId, existing.getId())
                            .set(KnowledgeDocumentScheduleDO::getCronExpr, cron)
                            .set(KnowledgeDocumentScheduleDO::getEnabled, enabled ? 1 : 0)
                            .set(KnowledgeDocumentScheduleDO::getNextRunTime, nextRunTime)
            );
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocId(String docId) {
        if (!StringUtils.hasText(docId)) {
            return;
        }
        scheduleExecMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentScheduleExecDO>()
                .eq(KnowledgeDocumentScheduleExecDO::getDocId, docId));
        scheduleMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentScheduleDO>()
                .eq(KnowledgeDocumentScheduleDO::getDocId, docId));
    }
}
