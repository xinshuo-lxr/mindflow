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

package com.xinshuo.mindflow.rag.core.prompt;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示模板加载器，从 classpath 加载 .st 模板并支持变量填充和 section 解析
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateLoader {

    private final ResourceLoader resourceLoader;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> sectionCache = new ConcurrentHashMap<>();

    /**
     * 加载模板原文（带缓存）
     */
    public String load(String path) {
        if (StrUtil.isBlank(path)) {
            throw new IllegalArgumentException("提示模板路径为空");
        }
        return cache.computeIfAbsent(path, this::readResource);
    }

    /**
     * 加载模板并填充占位符 {key}
     */
    public String render(String path, Map<String, String> slots) {
        String template = load(path);
        String filled = PromptTemplateUtils.fillSlots(template, slots);
        return PromptTemplateUtils.cleanupPrompt(filled);
    }

    /**
     * 加载模板中指定 section 的原始内容
     */
    public String loadSection(String path, String section) {
        Map<String, String> sections = sectionCache.computeIfAbsent(path, p -> {
            String content = load(p);
            return PromptTemplateUtils.parseSections(content);
        });
        String template = sections.get(section);
        if (template == null) {
            throw new IllegalStateException("模板 section 不存在：" + path + " -> " + section);
        }
        return template;
    }

    /**
     * 加载模板中指定 section 并填充占位符
     */
    public String renderSection(String path, String section, Map<String, String> slots) {
        String template = loadSection(path, section);
        String filled = PromptTemplateUtils.fillSlots(template, slots);
        return PromptTemplateUtils.cleanupPrompt(filled);
    }

    private String readResource(String path) {
        String location = path.startsWith("classpath:") ? path : "classpath:" + path;
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词模板路径不存在：" + path);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取提示模板失败，路径：{}", path, e);
            throw new IllegalStateException("读取提示模板失败，路径：" + path, e);
        }
    }
}
