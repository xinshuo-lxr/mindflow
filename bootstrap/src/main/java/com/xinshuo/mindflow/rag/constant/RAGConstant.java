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

package com.xinshuo.mindflow.rag.constant;

/**
 * RAG 系统常量类
 * <p>
 * 定义 RAG 系统中使用的各种常量配置，包括不限于：
 * <ul>
 *   <li>意图识别相关阈值和限制</li>
 *   <li>查询改写提示词模板</li>
 *   <li>RAG 问答提示词模板</li>
 *   <li>系统对话提示词模板</li>
 *   <li>对话记忆压缩/标题生成提示词模板</li>
 * </ul>
 * 这些常量主要用于控制 RAG 系统的行为和生成质量，包括意图过滤、查询优化、
 * 文档检索和智能问答等核心流程。
 */
public class RAGConstant {

    // ==================== 意图识别（第 11 步） ====================

    /**
     * 意图识别最低分数阈值——低于此分数视为"聊偏了"，不参与 RAG 检索
     */
    public static final double INTENT_MIN_SCORE = 0.35;

    /**
     * 单次查询最多参与的意图数量上限，防止拉取过多 Collection 导致性能问题
     */
    public static final int MAX_INTENT_COUNT = 3;

    /**
     * 多通道检索占位符键——没有意图识别结果时作为 intentChunks Map 的占位符，
     * 实际处理时只使用 Map 的 values，不关心具体 key
     */
    public static final String MULTI_CHANNEL_KEY = "multi_channel";

    // ==================== 提示模板路径 ====================

    /** 意图识别提示词模板路径（串行模式，一次性发送所有意图节点给 LLM 识别） */
    public static final String INTENT_CLASSIFIER_PROMPT_PATH = "prompt/intent-classifier.st";

    /** 引导式问答提示词模板路径 */
    public static final String GUIDANCE_PROMPT_PATH = "prompt/guidance-prompt.st";

    /** 歧义确认提示词模板路径——边界 case 调 LLM 二次确认品类歧义 */
    public static final String GUIDANCE_AMBIGUITY_CHECK_PROMPT_PATH = "prompt/guidance-ambiguity-check.st";

    /** 系统对话提示词模板路径——定义企业知识助手的角色设定和对话规则 */
    public static final String CHAT_SYSTEM_PROMPT_PATH = "prompt/answer-chat-system.st";

    /** 查询改写 + 多问句拆分提示词模板路径 */
    public static final String QUERY_REWRITE_AND_SPLIT_PROMPT_PATH = "prompt/user-question-rewrite.st";

    /**
     * 对话记忆压缩提示词模板路径
     * <p>模板参数：{@code {summary_max_chars}}——摘要长度上限（字符数）</p>
     */
    public static final String CONVERSATION_SUMMARY_PROMPT_PATH = "prompt/conversation-summary.st";

    /**
     * 会话标题生成提示词模板路径
     * <p>模板参数：{@code {title_max_chars}}——标题长度上限，{@code {question}}——用户问题</p>
     */
    public static final String CONVERSATION_TITLE_PROMPT_PATH = "prompt/conversation-title.st";

    /** 默认 RAG 问答提示词模板路径——基于检索文档回答，含严格事实性约束 */
    public static final String RAG_ENTERPRISE_PROMPT_PATH = "prompt/answer-chat-kb.st";

    /** MCP 工具参数提取提示词模板路径 */
    public static final String MCP_PARAMETER_EXTRACT_PROMPT_PATH = "prompt/mcp-parameter-extract.st";

    /** MCP 工具参数提取用户消息提示词模板路径 */
    public static final String MCP_PARAMETER_EXTRACT_USER_PROMPT_PATH = "prompt/mcp-parameter-extract-user.st";

    /** MCP-only 场景提示词模板路径——仅动态数据片段时使用 */
    public static final String MCP_ONLY_PROMPT_PATH = "prompt/answer-chat-mcp.st";

    /** MCP + KB 混合场景提示词模板路径 */
    public static final String MCP_KB_MIXED_PROMPT_PATH = "prompt/answer-chat-mcp-kb-mixed.st";

    // ==================== 上下文格式化 ====================

    /**
     * 上下文格式化模板文件路径
     * <p>包含所有上下文格式化所需的 section，通过 {@code --- section: name ---} 分隔，
     * 使用 {@code PromptTemplateLoader.renderSection(path, section, slots)} 渲染</p>
     */
    public static final String CONTEXT_FORMAT_PATH = "prompt/context-format.st";
}
