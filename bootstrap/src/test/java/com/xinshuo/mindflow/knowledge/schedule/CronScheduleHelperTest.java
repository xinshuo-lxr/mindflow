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

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cron 工具类单元测试
 */
class CronScheduleHelperTest {

    private Date dateOf(int year, int month, int day, int hour, int minute) {
        return Date.from(LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void shouldComputeNextRunTime() {
        Date from = dateOf(2026, 11, 8, 10, 3);
        Date next = CronScheduleHelper.nextRunTime("0 */5 * * * *", from);
        assertNotNull(next, "应能算出下次执行时间");
        LocalDateTime nextTime = LocalDateTime.ofInstant(next.toInstant(), ZoneId.systemDefault());
        assertEquals(5, nextTime.getMinute(), "每 5 分钟执行，下次应为 10:05");
        assertEquals(10, nextTime.getHour());
    }

    @Test
    void shouldReturnNullForBlankCron() {
        assertNull(CronScheduleHelper.nextRunTime("", new Date()));
        assertNull(CronScheduleHelper.nextRunTime(null, new Date()));
        assertNull(CronScheduleHelper.nextRunTime("0 */5 * * * *", null));
    }

    @Test
    void shouldDetectTooFrequentInterval() {
        Date from = dateOf(2026, 11, 8, 10, 0);
        assertTrue(CronScheduleHelper.isIntervalLessThan("*/30 * * * * *", from, 60),
                "每 30 秒的周期小于 60 秒");
        assertFalse(CronScheduleHelper.isIntervalLessThan("0 */5 * * * *", from, 60),
                "每 5 分钟的周期不小于 60 秒");
    }

    @Test
    void shouldRejectInvalidCron() {
        assertThrows(IllegalArgumentException.class,
                () -> CronScheduleHelper.nextRunTime("not-a-cron", new Date()));
    }
}
