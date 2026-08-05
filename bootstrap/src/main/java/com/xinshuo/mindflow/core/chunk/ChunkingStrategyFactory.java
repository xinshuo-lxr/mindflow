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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 文档切分策略工厂
 */
@Component
@RequiredArgsConstructor
public class ChunkingStrategyFactory {

    private final List<ChunkingStrategy> chunkingStrategies;
    private volatile Map<ChunkingMode, ChunkingStrategy> strategies = Map.of();

    public Optional<ChunkingStrategy> findStrategy(ChunkingMode type) {
        if (type == null) return Optional.empty();
        return Optional.ofNullable(strategies.get(type));
    }

    public ChunkingStrategy requireStrategy(ChunkingMode type) {
        Objects.requireNonNull(type, "ChunkingMode type must not be null");
        return findStrategy(type)
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + type));
    }

    @PostConstruct
    public void init() {
        Map<ChunkingMode, ChunkingStrategy> map = new EnumMap<>(ChunkingMode.class);
        chunkingStrategies.forEach(s -> {
            ChunkingStrategy old = map.put(s.getType(), s);
            if (old != null) {
                throw new IllegalStateException(
                        "Duplicate ChunkingStrategy for type: " + s.getType());
            }
        });
        this.strategies = Map.copyOf(map);
    }
}
