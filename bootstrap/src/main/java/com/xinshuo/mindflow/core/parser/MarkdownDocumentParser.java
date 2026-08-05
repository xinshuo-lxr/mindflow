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

package com.xinshuo.mindflow.core.parser;

import com.xinshuo.mindflow.core.parser.model.Block;
import com.xinshuo.mindflow.core.parser.model.CodeBlock;
import com.xinshuo.mindflow.core.parser.model.HeadingBlock;
import com.xinshuo.mindflow.core.parser.model.ListBlock;
import com.xinshuo.mindflow.core.parser.model.ParagraphBlock;
import com.xinshuo.mindflow.core.parser.model.ParsedDocument;
import com.xinshuo.mindflow.core.parser.model.Provenance;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Markdown 文档解析器
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MarkdownDocumentParser implements DocumentParser {

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    @Override
    public String getParserType() { return ParserType.MARKDOWN.getType(); }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) return ParsedDocument.of(List.of());
        String text = new String(content, StandardCharsets.UTF_8);
        Provenance prov = Provenance.ofFile(extractSourceFile(options));
        Document doc = (Document) PARSER.parse(text);
        BlockExtractingVisitor visitor = new BlockExtractingVisitor(prov);
        doc.accept(visitor);
        return ParsedDocument.of(visitor.getBlocks(), Map.of(
                "parser", getParserType(), "mimeType", mimeType == null ? "" : mimeType, "blocks", visitor.getBlocks().size()));
    }

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && (mimeType.equals("text/markdown") || mimeType.equals("text/x-markdown")
                || mimeType.equals("text/plain"));
    }

    private static String extractSourceFile(Map<String, Object> options) {
        if (options == null) return "";
        Object v = options.get("sourceFile");
        return v == null ? "" : v.toString();
    }

    private static final class BlockExtractingVisitor extends AbstractVisitor {
        private final Provenance provenance;
        private final List<Block> blocks = new ArrayList<>();
        BlockExtractingVisitor(Provenance p) { this.provenance = p; }
        List<Block> getBlocks() { return blocks; }

        @Override public void visit(Heading h) {
            blocks.add(new HeadingBlock(UUID.randomUUID().toString(), provenance, Collections.emptyList(),
                    h.getLevel(), extractInlineText(h)));
        }
        @Override public void visit(Paragraph p) {
            if (p.getParent() instanceof ListItem) return;
            String t = extractInlineText(p);
            if (!t.isEmpty()) blocks.add(new ParagraphBlock(UUID.randomUUID().toString(), provenance, Collections.emptyList(), t));
        }
        @Override public void visit(FencedCodeBlock cb) {
            blocks.add(new CodeBlock(UUID.randomUUID().toString(), provenance, Collections.emptyList(),
                    cb.getInfo(), stripTrailingNewline(cb.getLiteral())));
        }
        @Override public void visit(IndentedCodeBlock cb) {
            blocks.add(new CodeBlock(UUID.randomUUID().toString(), provenance, Collections.emptyList(),
                    null, stripTrailingNewline(cb.getLiteral())));
        }
        @Override public void visit(BulletList bl) { blocks.add(buildList(bl, false)); }
        @Override public void visit(OrderedList ol) { blocks.add(buildList(ol, true)); }
        @Override public void visit(org.commonmark.node.CustomBlock cb) {
            if (cb instanceof TableBlock tb) { handleTable(tb); return; }
            super.visit(cb);
        }

        private ListBlock buildList(org.commonmark.node.Node listNode, boolean ordered) {
            List<String> items = new ArrayList<>();
            org.commonmark.node.Node child = listNode.getFirstChild();
            while (child != null) {
                if (child instanceof ListItem) items.add(extractInlineText(child).trim());
                child = child.getNext();
            }
            return new ListBlock(UUID.randomUUID().toString(), provenance, Collections.emptyList(), ordered, items);
        }

        private void handleTable(TableBlock tableBlock) {
            List<String> headers = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            var child = tableBlock.getFirstChild();
            while (child != null) {
                if (child instanceof TableHead head) {
                    var hr = head.getFirstChild();
                    if (hr instanceof TableRow tr) headers.addAll(cellTexts(tr));
                } else if (child instanceof TableBody body) {
                    var tr = body.getFirstChild();
                    while (tr != null) {
                        if (tr instanceof TableRow row) rows.add(cellTexts(row));
                        tr = tr.getNext();
                    }
                }
                child = child.getNext();
            }
            blocks.add(new com.xinshuo.mindflow.core.parser.model.TableBlock(
                    UUID.randomUUID().toString(), provenance, Collections.emptyList(), headers, rows, null));
        }

        private List<String> cellTexts(TableRow row) {
            List<String> cells = new ArrayList<>();
            var cell = row.getFirstChild();
            while (cell != null) {
                if (cell instanceof TableCell tc) cells.add(extractInlineText(tc).trim());
                cell = cell.getNext();
            }
            return cells;
        }
    }

    static String extractInlineText(org.commonmark.node.Node parent) {
        StringBuilder sb = new StringBuilder();
        var child = parent.getFirstChild();
        while (child != null) { appendInline(sb, child); child = child.getNext(); }
        return sb.toString();
    }

    static void appendInline(StringBuilder sb, org.commonmark.node.Node node) {
        if (node instanceof Text t) sb.append(t.getLiteral());
        else if (node instanceof Code c) sb.append('`').append(c.getLiteral()).append('`');
        else if (node instanceof Link l) sb.append('[').append(extractInlineText(l)).append("](").append(l.getDestination()).append(')');
        else if (node instanceof Emphasis || node instanceof StrongEmphasis) sb.append(extractInlineText(node));
        else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) sb.append('\n');
        else if (node.getFirstChild() != null) {
            var c = node.getFirstChild();
            while (c != null) { appendInline(sb, c); c = c.getNext(); }
        }
    }

    static String stripTrailingNewline(String s) {
        if (s == null) return "";
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }
}
