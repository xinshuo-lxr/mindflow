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

package com.xinshuo.mindflow.core.parser.model;

import java.util.List;

/**
 * 图片 Block
 *
 * @param asset       图片资产引用
 * @param caption     图片标题
 * @param altText     无障碍替代文本
 * @param description VLM 图生文结果
 */
public record ImageBlock(
        String id,
        Provenance provenance,
        List<String> outlinePath,
        AssetRef asset,
        String caption,
        String altText,
        String description
) implements Block {

    public ImageBlock(String id, Provenance provenance, List<String> outlinePath,
                      AssetRef asset, String caption, String altText) {
        this(id, provenance, outlinePath, asset, caption, altText, null);
    }
}
