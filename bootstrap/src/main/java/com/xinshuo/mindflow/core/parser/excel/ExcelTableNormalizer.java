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
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;

import java.util.ArrayList;
import java.util.List;

public final class ExcelTableNormalizer {

    public static final String HEADER_SEPARATOR = "|";

    private ExcelTableNormalizer() {
    }

    public record NormalizedTable(List<String> headers, List<List<String>> rows) {
        public boolean isEmpty() { return headers.isEmpty() && rows.isEmpty(); }
        static NormalizedTable empty() { return new NormalizedTable(List.of(), List.of()); }
    }

    public static NormalizedTable normalize(Sheet sheet, DataFormatter formatter,
                                            FormulaEvaluator evaluator, int headerRows) {
        if (headerRows < 1) throw new IllegalArgumentException("headerRows >= 1");
        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum < 0) return NormalizedTable.empty();

        int maxCol = computeMaxCol(sheet, lastRowNum);
        if (maxCol == 0) return NormalizedTable.empty();

        String[][] grid = readGrid(sheet, lastRowNum, maxCol, formatter, evaluator);
        expandMergedRegions(grid, sheet.getMergedRegions(), lastRowNum, maxCol);
        int[] cols = selectNonEmptyCols(grid, 0, lastRowNum, maxCol);
        if (cols.length == 0) return NormalizedTable.empty();

        int effectiveHdr = Math.min(headerRows, lastRowNum + 1);
        List<String> headers = flattenHeaders(grid, 0, effectiveHdr, cols);
        List<List<String>> rows = effectiveHdr <= lastRowNum
                ? collectRows(grid, effectiveHdr, lastRowNum, cols) : List.of();
        return new NormalizedTable(headers, rows);
    }

    private static int computeMaxCol(Sheet sheet, int lastRow) {
        int m = 0;
        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row != null) m = Math.max(m, row.getLastCellNum());
        }
        return m;
    }

    private static int[] selectNonEmptyCols(String[][] g, int sr, int er, int mc) {
        List<Integer> kept = new ArrayList<>();
        for (int c = 0; c < mc; c++) {
            for (int r = sr; r <= er; r++) {
                if (g[r][c] != null && !g[r][c].isEmpty()) { kept.add(c); break; }
            }
        }
        return kept.stream().mapToInt(i -> i).toArray();
    }

    private static String[][] readGrid(Sheet sheet, int lr, int mc,
                                       DataFormatter fmt, FormulaEvaluator ev) {
        String[][] g = new String[lr + 1][mc];
        for (int r = 0; r <= lr; r++) {
            Row row = sheet.getRow(r);
            for (int c = 0; c < mc; c++) {
                String v = "";
                if (row != null) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    if (cell != null) {
                        String f = ExcelValueFormatter.format(cell, fmt, ev);
                        v = ExcelHyperlinkResolver.wrap(f, cell);
                        if (!v.isEmpty() && ExcelValueFormatter.isStrikethrough(cell))
                            v = "~~" + v + "~~";
                    }
                }
                g[r][c] = v;
            }
        }
        return g;
    }

    private static void expandMergedRegions(String[][] g, List<CellRangeAddress> regions,
                                            int lr, int mc) {
        if (regions == null || regions.isEmpty()) return;
        for (CellRangeAddress rgn : regions) {
            int fr = rgn.getFirstRow(), fc = rgn.getFirstColumn();
            if (fr < 0 || fr > lr || fc < 0 || fc >= mc) continue;
            String v = g[fr][fc];
            if (v == null || v.isEmpty()) continue;
            int re = Math.min(rgn.getLastRow(), lr), ce = Math.min(rgn.getLastColumn(), mc - 1);
            for (int r = fr; r <= re; r++)
                for (int c = fc; c <= ce; c++) g[r][c] = v;
        }
    }

    private static List<String> flattenHeaders(String[][] g, int sr, int hr, int[] cols) {
        List<String> hdrs = new ArrayList<>();
        for (int c : cols) {
            StringBuilder sb = new StringBuilder();
            String prev = null;
            for (int r = sr; r < sr + hr; r++) {
                String v = g[r][c];
                if (v == null || v.isEmpty()) continue;
                if (v.equals(prev)) continue;
                if (!sb.isEmpty()) sb.append(HEADER_SEPARATOR);
                sb.append(v);
                prev = v;
            }
            hdrs.add(sb.toString());
        }
        return hdrs;
    }

    private static List<List<String>> collectRows(String[][] g, int sr, int er, int[] cols) {
        List<List<String>> rows = new ArrayList<>();
        for (int r = sr; r <= er; r++) {
            List<String> rv = new ArrayList<>();
            boolean allEmpty = true;
            for (int c : cols) {
                String v = g[r][c];
                if (v != null && !v.isEmpty()) allEmpty = false;
                rv.add(v == null ? "" : v);
            }
            if (!allEmpty) rows.add(rv);
        }
        return rows;
    }
}
