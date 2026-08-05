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

package com.xinshuo.mindflow.core.parser.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Hyperlink;

public final class ExcelHyperlinkResolver {

    private ExcelHyperlinkResolver() {
    }

    public static String wrap(String cellText, Cell cell) {
        if (cell == null) return cellText == null ? "" : cellText;
        Hyperlink hyperlink = cell.getHyperlink();
        if (hyperlink == null) return cellText == null ? "" : cellText;
        String url = hyperlink.getAddress();
        if (url == null || url.isBlank()) return cellText == null ? "" : cellText;
        String visible = (cellText == null || cellText.isEmpty()) ? hyperlink.getLabel() : cellText;
        if (visible == null || visible.isEmpty()) visible = url;
        return "[" + visible + "](" + url + ")";
    }
}
