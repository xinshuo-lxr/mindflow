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

package com.xinshuo.mindflow.infra.token;

import org.springframework.stereotype.Service;

/**
 * 简单 Token 统计实现：字符数 / 4 估算
 */
@Service
public class DefaultTokenCounterService implements TokenCounterService {

    @Override
    public Integer countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.length() / 4;
    }
}
