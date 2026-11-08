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

package com.xinshuo.mindflow.knowledge.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xinshuo.mindflow.MindflowApplication;
import com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeBaseDO;
import com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeDocumentDO;
import com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.xinshuo.mindflow.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.xinshuo.mindflow.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.xinshuo.mindflow.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.xinshuo.mindflow.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.xinshuo.mindflow.knowledge.enums.DocumentStatus;
import com.xinshuo.mindflow.knowledge.enums.SourceType;
import com.xinshuo.mindflow.knowledge.service.KnowledgeDocumentScheduleService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 定时刷新：租约锁 / 调度同步 / 卡死恢复 集成测试（第 21 步）
 *
 * <p>不触发真实拉取与分块，只验证调度侧核心状态机与 DB 锁语义。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScheduleLockAndRecoveryIntegrationTest {

    @Autowired
    private ScheduleLockManager lockManager;

    @Autowired
    private DocumentStatusHelper documentStatusHelper;

    @Autowired
    private KnowledgeDocumentScheduleService scheduleService;

    @Autowired
    private KnowledgeDocumentScheduleMapper scheduleMapper;

    @Autowired
    private KnowledgeDocumentScheduleExecMapper execMapper;

    @Autowired
    private KnowledgeDocumentMapper documentMapper;

    @Autowired
    private KnowledgeBaseMapper kbMapper;

    private static final String SUFFIX = String.valueOf(System.currentTimeMillis()).substring(7);
    private static final String KB_ID = "skb" + SUFFIX;
    private static final String DOC_ID = "sdc" + SUFFIX;
    private static final String STUCK_DOC_ID = "stk" + SUFFIX;

    private static String scheduleId;

    @Test
    @Order(1)
    void shouldPrepareDocumentAndSchedule() {
        KnowledgeBaseDO kb = KnowledgeBaseDO.builder()
                .id(KB_ID)
                .name("定时刷新测试库-" + SUFFIX)
                .embeddingModel("fake")
                .collectionName("skb-col-" + SUFFIX)
                .createdBy("system")
                .updatedBy("system")
                .build();
        kbMapper.insert(kb);

        KnowledgeDocumentDO doc = KnowledgeDocumentDO.builder()
                .id(DOC_ID)
                .kbId(KB_ID)
                .docName("remote-schedule.md")
                .sourceType(SourceType.URL.getValue())
                .sourceLocation("https://example.com/schedule.md")
                .fileUrl("file:///tmp/schedule-test-remote.md")
                .fileType("markdown")
                .fileSize(0L)
                .scheduleEnabled(1)
                .scheduleCron("0 */5 * * * *")
                .enabled(1)
                .chunkCount(0)
                .status(DocumentStatus.PENDING.getCode())
                .createdBy("system")
                .updatedBy("system")
                .build();
        documentMapper.insert(doc);

        scheduleService.upsertSchedule(doc);

        KnowledgeDocumentScheduleDO schedule = scheduleMapper.selectOne(
                Wrappers.lambdaQuery(KnowledgeDocumentScheduleDO.class)
                        .eq(KnowledgeDocumentScheduleDO::getDocId, DOC_ID));
        assertNotNull(schedule, "URL 文档开启定时后应生成调度记录");
        assertEquals(1, schedule.getEnabled());
        assertNotNull(schedule.getNextRunTime(), "应算出下次执行时间");
        scheduleId = schedule.getId();
        System.out.println("[1] 调度记录已创建: " + scheduleId + ", nextRun=" + schedule.getNextRunTime());
    }

    @Test
    @Order(2)
    void shouldAcquireRenewReleaseLease() {
        Date now = new Date();
        ScheduleLockLease lease = lockManager.tryAcquire(scheduleId, now);
        assertNotNull(lease, "首次抢锁应成功");

        ScheduleLockLease second = lockManager.tryAcquire(scheduleId, now);
        assertNull(second, "锁未过期时其他实例不应抢到");

        assertTrue(lockManager.renew(lease), "持锁者可续约");
        assertTrue(lockManager.release(lease), "持锁者可释放");

        ScheduleLockLease again = lockManager.tryAcquire(scheduleId, new Date());
        assertNotNull(again, "释放后应可重新抢锁");
        lockManager.release(again);
        System.out.println("[2] 租约锁 抢占/互斥/续约/释放 OK");
    }

    @Test
    @Order(3)
    void shouldDisableScheduleWhenDocumentDisabled() {
        KnowledgeDocumentDO doc = documentMapper.selectById(DOC_ID);
        doc.setEnabled(0);
        documentMapper.updateById(doc);
        scheduleService.syncScheduleIfExists(doc);

        KnowledgeDocumentScheduleDO schedule = scheduleMapper.selectById(scheduleId);
        assertEquals(0, schedule.getEnabled(), "文档禁用后调度应关闭");
        assertNull(schedule.getNextRunTime(), "关闭后应清空下次执行时间");

        // 恢复启用，便于后续用例
        doc.setEnabled(1);
        documentMapper.updateById(doc);
        scheduleService.syncScheduleIfExists(doc);
        schedule = scheduleMapper.selectById(scheduleId);
        assertEquals(1, schedule.getEnabled());
        System.out.println("[3] 文档启停联动调度 OK");
    }

    @Test
    @Order(4)
    void shouldRecoverStuckRunningDocument() {
        KnowledgeDocumentDO doc = KnowledgeDocumentDO.builder()
                .id(STUCK_DOC_ID)
                .kbId(KB_ID)
                .docName("stuck.md")
                .sourceType(SourceType.FILE.getValue())
                .fileUrl("file:///tmp/stuck-test.md")
                .fileType("markdown")
                .fileSize(0L)
                .enabled(1)
                .chunkCount(0)
                .status(DocumentStatus.RUNNING.getCode())
                .createdBy("system")
                .updatedBy("system")
                .build();
        documentMapper.insert(doc);

        // 模拟两小时前开始分块
        documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getUpdateTime, LocalDateTime.now().minusHours(2))
                        .eq(KnowledgeDocumentDO::getId, STUCK_DOC_ID));

        DocumentStatusHelper.StuckRecoveryResult result = documentStatusHelper.recoverStuckRunning(30);
        assertTrue(result.stuckDocIds().contains(STUCK_DOC_ID), "应识别出卡死文档");
        assertTrue(result.actualRecovered() >= 1, "至少恢复当前卡死文档");

        KnowledgeDocumentDO after = documentMapper.selectById(STUCK_DOC_ID);
        assertEquals(DocumentStatus.FAILED.getCode(), after.getStatus(), "卡死文档应回置为 FAILED");
        System.out.println("[4] 卡死恢复 OK, 候选=" + result.stuckDocIds().size());
    }

    @Test
    @Order(5)
    void shouldDeleteScheduleAndExecsByDocId() {
        scheduleService.deleteByDocId(DOC_ID);

        List<KnowledgeDocumentScheduleDO> schedules = scheduleMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeDocumentScheduleDO.class)
                        .eq(KnowledgeDocumentScheduleDO::getDocId, DOC_ID));
        assertTrue(schedules.isEmpty(), "删除文档应级联删除调度记录");

        Long execCount = execMapper.selectCount(
                Wrappers.lambdaQuery(com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO.class)
                        .eq(com.xinshuo.mindflow.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO::getDocId, DOC_ID));
        assertEquals(0L, execCount, "执行记录也应被清理");
        System.out.println("[5] 级联删除调度与执行记录 OK");
    }
}
