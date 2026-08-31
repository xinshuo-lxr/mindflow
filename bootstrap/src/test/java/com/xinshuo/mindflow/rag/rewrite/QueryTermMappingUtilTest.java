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

package com.xinshuo.mindflow.rag.rewrite;

import com.xinshuo.mindflow.rag.core.rewrite.QueryTermMappingUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 术语归一化替换工具单元测试（纯逻辑，无 Spring 依赖）
 */
class QueryTermMappingUtilTest {

    @Test
    void shouldReplaceSourceWithTarget() {
        assertEquals("平安保险公司 理赔流程",
                QueryTermMappingUtil.applyMapping("平安保司 理赔流程", "平安保司", "平安保险公司"));
    }

    @Test
    void shouldReplaceMultipleOccurrences() {
        assertEquals("平安保险公司对接平安保险公司",
                QueryTermMappingUtil.applyMapping("平安保司对接平安保司", "平安保司", "平安保险公司"));
    }

    @Test
    void shouldNotDoubleReplaceWhenAlreadyTarget() {
        // 文本中已经包含目标词，不应重复替换成"平安保险公司平安保险公司"
        assertEquals("平安保险公司 理赔流程",
                QueryTermMappingUtil.applyMapping("平安保险公司 理赔流程", "平安保司", "平安保险公司"));
    }

    @Test
    void shouldKeepTextWhenNoMatch() {
        assertEquals("OA系统审批流程",
                QueryTermMappingUtil.applyMapping("OA系统审批流程", "平安保司", "平安保险公司"));
    }

    @Test
    void shouldReturnNullForNullInput() {
        assertNull(QueryTermMappingUtil.applyMapping(null, "a", "b"));
    }

    @Test
    void shouldReturnSameForNullSource() {
        assertEquals("原始文本", QueryTermMappingUtil.applyMapping("原始文本", null, "b"));
    }

    @Test
    void shouldHandleEmptySource() {
        assertEquals("原始文本", QueryTermMappingUtil.applyMapping("原始文本", "", "b"));
    }
}
