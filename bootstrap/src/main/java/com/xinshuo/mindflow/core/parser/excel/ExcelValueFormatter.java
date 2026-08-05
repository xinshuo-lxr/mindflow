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

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Workbook;

@Slf4j
public final class ExcelValueFormatter {

    private ExcelValueFormatter() {
    }

    public static String format(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA) return formatFormulaCell(cell, formatter, evaluator);
        return formatter.formatCellValue(cell).trim();
    }

    private static String formatFormulaCell(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (evaluator != null) {
            try { return formatter.formatCellValue(cell, evaluator).trim(); } catch (Exception ignored) {}
        }
        try {
            CellType cachedType = cell.getCachedFormulaResultType();
            return switch (cachedType) {
                case NUMERIC -> formatter.formatCellValue(cell).trim();
                case STRING -> cell.getStringCellValue().trim();
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case ERROR -> "";
                default -> cell.getCellFormula();
            };
        } catch (Exception ignored) {}
        try { return cell.getCellFormula(); } catch (Exception ignored) { return ""; }
    }

    public static boolean isStrikethrough(Cell cell) {
        if (cell == null) return false;
        try {
            CellStyle style = cell.getCellStyle();
            if (style == null) return false;
            Workbook workbook = cell.getSheet().getWorkbook();
            Font font = workbook.getFontAt(style.getFontIndex());
            return font != null && font.getStrikeout();
        } catch (Exception e) { return false; }
    }
}
