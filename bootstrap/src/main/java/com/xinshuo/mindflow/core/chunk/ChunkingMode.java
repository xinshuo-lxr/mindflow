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

package com.xinshuo.mindflow.core.chunk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.Map;

/**
 * 文档分块策略枚举
 */
@Getter
public enum ChunkingMode {

    FIXED_SIZE("fixed_size", "固定大小", true) {
        @Override
        public ChunkingOptions createOptions(Map<String, Object> config) {
            return new FixedSizeOptions(
                    toInt(config, "chunkSize", 512),
                    toInt(config, "overlapSize", 128));
        }

        @Override
        public ChunkingOptions createDefaultOptions(Integer targetSize, Integer overlapSize) {
            return new FixedSizeOptions(
                    targetSize != null ? targetSize : 512,
                    overlapSize != null ? overlapSize : 128);
        }
    },

    STRUCTURE_AWARE("structure_aware", "语义感知（Markdown友好）", true) {
        @Override
        public ChunkingOptions createOptions(Map<String, Object> config) {
            return new TextBoundaryOptions(
                    toInt(config, "targetChars", 1400),
                    toInt(config, "overlapChars", 0),
                    toInt(config, "maxChars", 1800),
                    toInt(config, "minChars", 600));
        }

        @Override
        public ChunkingOptions createDefaultOptions(Integer targetSize, Integer overlapSize) {
            return new TextBoundaryOptions(
                    targetSize != null ? targetSize : 1400,
                    overlapSize != null ? overlapSize : 0,
                    1800,
                    600);
        }
    };

    private final String value;
    private final String label;
    private final boolean visible;

    ChunkingMode(String value, String label, boolean visible) {
        this.value = value;
        this.label = label;
        this.visible = visible;
    }

    public Map<String, Integer> getDefaultConfig() {
        return createOptions(Map.of()).toConfigMap();
    }

    public abstract ChunkingOptions createOptions(Map<String, Object> config);

    public abstract ChunkingOptions createDefaultOptions(Integer targetSize, Integer overlapSize);

    static int toInt(Map<String, Object> config, String key, int defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number num) return num.intValue();
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @JsonCreator
    public static ChunkingMode fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase().replace('-', '_');
        for (ChunkingMode strategy : values()) {
            if (strategy.value.equalsIgnoreCase(normalized) || strategy.name().equalsIgnoreCase(normalized)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("Unknown chunk strategy: " + value);
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
