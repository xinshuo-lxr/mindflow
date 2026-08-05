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

import com.xinshuo.mindflow.core.parser.DocumentParser;
import com.xinshuo.mindflow.core.parser.ParserType;
import com.xinshuo.mindflow.core.parser.model.Block;
import com.xinshuo.mindflow.core.parser.model.ParsedDocument;
import com.xinshuo.mindflow.core.parser.model.Provenance;
import com.xinshuo.mindflow.core.parser.model.TableBlock;
import com.xinshuo.mindflow.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ExcelDocumentParser implements DocumentParser {

    @Override
    public String getParserType() { return ParserType.EXCEL_POI.getType(); }

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                || mimeType.equals("application/vnd.ms-excel")
                || mimeType.equals("application/x-tika-msoffice")
                || mimeType.equals("application/x-tika-ooxml");
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) return ParsedDocument.of(List.of());
        String sourceFile = str(options, "sourceFile", "");
        int headerRows = num(options, "headerRows", 1);
        List<Block> blocks = new ArrayList<>();
        int totalSheets;
        try (ByteArrayInputStream is = new ByteArrayInputStream(content);
             Workbook wb = WorkbookFactory.create(is)) {
            DataFormatter fmt = new DataFormatter();
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            totalSheets = wb.getNumberOfSheets();
            for (int i = 0; i < totalSheets; i++) {
                if (wb.isSheetHidden(i) || wb.isSheetVeryHidden(i)) continue;
                Sheet sheet = wb.getSheetAt(i);
                var t = ExcelTableNormalizer.normalize(sheet, fmt, ev, headerRows);
                if (t.isEmpty()) continue;
                blocks.add(new TableBlock(UUID.randomUUID().toString(),
                        Provenance.ofExcelCell(sourceFile, sheet.getSheetName()),
                        List.of(), t.headers(), t.rows(), null));
            }
        } catch (Exception e) {
            throw new ServiceException("Excel 解析失败: " + e.getMessage());
        }
        return ParsedDocument.of(blocks, Map.of("parser", getParserType(), "mimeType",
                mimeType == null ? "" : mimeType, "sheets", totalSheets, "tables", blocks.size()));
    }

    private static String str(Map<String, Object> opts, String key, String def) {
        if (opts == null) return def;
        Object v = opts.get(key);
        return v == null || v.toString().isBlank() ? def : v.toString();
    }

    private static int num(Map<String, Object> opts, String key, int def) {
        if (opts == null) return def;
        Object v = opts.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null) try { return Integer.parseInt(v.toString()); } catch (NumberFormatException ignored) {}
        return def;
    }
}
