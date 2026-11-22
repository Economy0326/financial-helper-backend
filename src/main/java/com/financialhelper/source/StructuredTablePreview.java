package com.financialhelper.source;

import org.jsoup.nodes.Element;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** In-memory DOM table inventory. Locators address this preview, never normalized document offsets. */
public final class StructuredTablePreview {
    public Result extract(Element root) {
        var tables = new ArrayList<Table>();
        int index = 0;
        for (Element table : root.select("table")) {
            String tableLocator = "preview-table-v1:" + index++;
            var cells = new ArrayList<Cell>();
            Set<String> occupied = new HashSet<>();
            boolean uncertain = table.select("table table,img,svg,iframe,object").size() > 0;
            var rows = table.select("tr").stream().filter(r -> r.closest("table") == table).toList();
            if (rows.isEmpty() || rows.size() > 200) uncertain = true;
            int rowIndex = 0;
            for (Element row : rows) {
                if (rowIndex >= 200) break;
                int column = 0;
                for (Element cell : row.children()) {
                    if (!cell.normalName().equals("td") && !cell.normalName().equals("th")) { uncertain = true; continue; }
                    while (occupied.contains(rowIndex + ":" + column) && column < 100) column++;
                    int rowspan = span(cell, "rowspan"), colspan = span(cell, "colspan");
                    if (rowspan < 1 || colspan < 1 || column + colspan > 100 || rowIndex + rowspan > rows.size()) { uncertain = true; continue; }
                    for (int r = rowIndex; r < rowIndex + rowspan; r++) for (int c = column; c < column + colspan; c++)
                        if (!occupied.add(r + ":" + c)) uncertain = true;
                    cells.add(new Cell(rowIndex, column, rowspan, colspan, cell.normalName().equals("th"),
                            cell.text(), tableLocator + ":r" + rowIndex + ":c" + column));
                    column += colspan;
                }
                rowIndex++;
            }
            if (cells.isEmpty() || cells.stream().noneMatch(Cell::header)) uncertain = true;
            var caption = table.selectFirst("caption");
            tables.add(new Table(tableLocator, caption == null ? null : caption.text(), List.copyOf(cells),
                    uncertain ? "HOLD" : "REVIEW_REQUIRED"));
        }
        return new Result("preview-table-v1", List.copyOf(tables), "CONTENT_REVIEW_REQUIRED");
    }
    private int span(Element element, String name) {
        if (!element.hasAttr(name)) return 1;
        try { int value = Integer.parseInt(element.attr(name)); return value > 0 && value <= 100 ? value : -1; }
        catch (NumberFormatException ex) { return -1; }
    }
    public record Cell(int row, int column, int rowSpan, int columnSpan, boolean header, String text, String locator) {}
    public record Table(String locator, String caption, List<Cell> cells, String status) {}
    public record Result(String version, List<Table> tables, String status) {}
}
