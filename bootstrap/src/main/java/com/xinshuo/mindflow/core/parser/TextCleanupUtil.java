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

package com.xinshuo.mindflow.core.parser;

/**
 * 文本清理工具类
 */
public final class TextCleanupUtil {

    private TextCleanupUtil() {
    }

    public static String cleanup(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text
                .replace("﻿", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    public static String cleanup(String text,
                                 boolean removeBOM,
                                 boolean trimTrailingSpaces,
                                 boolean compressEmptyLines,
                                 int maxConsecutiveLines) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = text;
        if (removeBOM) {
            result = result.replace("﻿", "");
        }
        if (trimTrailingSpaces) {
            result = result.replaceAll("[ \\t]+\\n", "\n");
        }
        if (compressEmptyLines && maxConsecutiveLines > 0) {
            String pattern = "\\n{" + (maxConsecutiveLines + 1) + ",}";
            String replacement = "\n".repeat(maxConsecutiveLines);
            result = result.replaceAll(pattern, replacement);
        }
        return result.trim();
    }
}
