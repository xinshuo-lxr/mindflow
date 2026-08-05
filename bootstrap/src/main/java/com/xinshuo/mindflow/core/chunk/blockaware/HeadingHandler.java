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

package com.xinshuo.mindflow.core.chunk.blockaware;

import com.xinshuo.mindflow.core.parser.model.HeadingBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class HeadingHandler {

    public List<String> update(List<String> currentPath, HeadingBlock heading) {
        if (heading == null) return currentPath;
        int targetLevel = Math.max(1, heading.level());
        int keep = Math.min(currentPath.size(), targetLevel - 1);
        List<String> next = new ArrayList<>(keep + 1);
        for (int i = 0; i < keep; i++) next.add(currentPath.get(i));
        next.add(heading.text() == null ? "" : heading.text());
        return List.copyOf(next);
    }
}
