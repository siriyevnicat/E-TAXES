package az.gmb.taxdata.service;

import az.gmb.taxdata.model.BatchManifest;
import az.gmb.taxdata.model.InvoiceData;
import az.gmb.taxdata.util.ExcelUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class PrintPacketService {
    private final StorageService storage;
    private final ObjectMapper mapper;

    public PrintPacketService(StorageService storage, ObjectMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
    }

    /**
     * Ön baxış və çap artıq ayrıca HTML şablonundan yaradılmır. Birbaşa yaradılmış
     * XLSX sənədinin hüceyrə ölçüləri, merge-ləri, fontları, border-ləri və hizalanması
     * oxunur. Beləliklə istifadəçi Excel-də nə görürsə, ön baxış/çap da eyni şablonu
     * əsas götürür.
     */
    public String render(String workspaceId, String folder, int hfCopies, int ttCopies, int qrCopies,
                         boolean autoPrint, boolean preview) throws IOException {
        Path dir = storage.resolveOutputFolder(workspaceId, folder);
        BatchManifest manifest = mapper.readValue(dir.resolve("manifest.json").toFile(), BatchManifest.class);
        hfCopies = clamp(hfCopies, 0, 20);
        ttCopies = clamp(ttCopies, 0, 20);
        qrCopies = clamp(qrCopies, 0, 20);

        StringBuilder pages = new StringBuilder(64_000);
        for (InvoiceData inv : manifest.invoices()) {
            String key = invoiceKey(inv);
            String docNo = manifest.documentNumbers() == null
                    ? safe(manifest.startingDocumentNo())
                    : manifest.documentNumbers().getOrDefault(key, safe(manifest.startingDocumentNo()));
            String mtSafe = sanitize(firstNonBlank(inv.getMtNumber(), key));

            appendWorkbookCopies(pages, dir.resolve("01_Hesab_Faktura_" + docNo + "_" + mtSafe + ".xlsx"), hfCopies);
            appendWorkbookCopies(pages, dir.resolve("03_Tehvil_Teslim_" + docNo + "_" + mtSafe + ".xlsx"), ttCopies);
            appendWorkbookCopies(pages, dir.resolve("02_Qiymet_Razilasma_" + docNo + "_" + mtSafe + ".xlsx"), qrCopies);
        }

        String banner = preview
                ? "<div class='preview-banner'>Ön baxış · görünüş birbaşa yaradılmış Excel şablonundan götürülür.</div>"
                : "";
        String script = autoPrint
                ? "<script>window.addEventListener('load',()=>setTimeout(()=>window.print(),450));</script>"
                : "";

        return """
<!doctype html>
<html lang='az'>
<head>
<meta charset='UTF-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<title>Alt sənədlər</title>
<style>
@page{size:A4 portrait;margin:7.62mm 5.59mm}
*{box-sizing:border-box}
html,body{margin:0;padding:0}
body{background:#e8ebef;color:#000;font-family:Arial,sans-serif;-webkit-print-color-adjust:exact;print-color-adjust:exact}
.preview-banner{position:sticky;top:0;z-index:20;padding:9px 14px;background:#172033;color:#fff;font:600 13px/1.3 Arial,sans-serif;box-shadow:0 1px 4px rgba(0,0,0,.25)}
.sheet-page{width:210mm;min-height:297mm;margin:8px auto;background:#fff;padding:7.62mm 5.59mm;box-shadow:0 2px 16px rgba(0,0,0,.18);break-after:page;page-break-after:always;overflow:hidden}
.sheet-page:last-child{break-after:auto;page-break-after:auto}
.sheet-fit{width:100%;overflow:hidden}
.sheet-scale{width:100%;transform-origin:top left}
.excel-sheet{width:100%;border-collapse:collapse;border-spacing:0;table-layout:fixed;margin:0;padding:0}
.excel-sheet td{padding:0 2px;overflow:hidden;vertical-align:bottom;line-height:1.12;word-break:normal;overflow-wrap:break-word}
.excel-sheet tr{break-inside:avoid;page-break-inside:avoid}
.cell-text{width:100%;min-height:100%;white-space:pre-wrap;overflow-wrap:break-word;word-break:normal}
@media(max-width:900px){.sheet-page{width:100%;min-height:0;margin:0 auto 10px;padding:8px;box-shadow:none}.preview-banner{position:static}}
@media print{
  body{background:#fff}
  .preview-banner{display:none!important}
  .sheet-page{width:auto;min-height:0;margin:0;padding:0;box-shadow:none;overflow:visible}
  .excel-sheet{width:100%!important}
}
</style>
</head>
<body>
""" + banner + pages + script + "</body></html>";
    }

    private void appendWorkbookCopies(StringBuilder out, Path file, int copies) throws IOException {
        if (copies <= 0) return;
        if (!Files.isRegularFile(file)) throw new IOException("Çap üçün yaradılmış Excel tapılmadı: " + file.getFileName());
        String html = workbookHtml(file);
        for (int i = 0; i < copies; i++) out.append(html);
    }

    private String workbookHtml(Path file) throws IOException {
        try (var in = Files.newInputStream(file); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheet("ŞABLON");
            if (sheet == null && wb.getNumberOfSheets() > 0) sheet = wb.getSheetAt(0);
            if (sheet == null) throw new IOException("Excel vərəqi tapılmadı: " + file.getFileName());

            Bounds bounds = bounds(wb, sheet);
            DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("az-AZ"));
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            MergeIndex merges = new MergeIndex(sheet, bounds);

            double totalWidth = 0;
            double[] widths = new double[bounds.lastCol - bounds.firstCol + 1];
            for (int c = bounds.firstCol; c <= bounds.lastCol; c++) {
                double w = sheet.isColumnHidden(c) ? 0 : Math.max(1d, sheet.getColumnWidth(c) / 256d);
                widths[c - bounds.firstCol] = w;
                totalWidth += w;
            }
            if (totalWidth <= 0) totalWidth = widths.length;

            double totalHeightPt = 0d;
            for (int r = bounds.firstRow; r <= bounds.lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row != null && row.getZeroHeight()) continue;
                double h = row == null || row.getHeightInPoints() <= 0
                        ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints();
                totalHeightPt += Math.max(0d, h);
            }
            // Excel faylında FitHeight=1 yalnız məhsulların hamısı 1-ci səhifəyə sığdığı,
            // amma aşağı yekun/qeyd/imza hissəsi növbəti səhifəyə daşdığı halda yazılır.
            // Sayt ön görünüşü və brauzer çapı həmin qərarı eyni qaydada təkrarlayır.
            PrintSetup printSetup = sheet.getPrintSetup();
            boolean fitWholeDocumentToOnePage = printSetup != null && printSetup.getFitHeight() == 1;
            double pageHeightPt = 297d / 25.4d * 72d;
            double printableHeightPt = pageHeightPt
                    - (sheet.getMargin(Sheet.TopMargin) + sheet.getMargin(Sheet.BottomMargin)) * 72d;
            double verticalScale = fitWholeDocumentToOnePage && totalHeightPt > printableHeightPt
                    ? Math.max(0.1d, Math.min(1d, printableHeightPt / totalHeightPt)) : 1d;
            double renderedHeightPt = Math.max(1d, totalHeightPt * verticalScale);

            StringBuilder html = new StringBuilder(20_000);
            html.append("<section class='sheet-page'><div class='sheet-fit' style='height:")
                    .append(fmt(renderedHeightPt)).append("pt'><div class='sheet-scale' style='transform:scaleY(")
                    .append(fmt(verticalScale)).append(")'>")
                    .append("<table class='excel-sheet'><colgroup>");
            for (double w : widths) {
                double pct = 100d * w / totalWidth;
                html.append("<col style='width:").append(fmt(pct)).append("%'>");
            }
            html.append("</colgroup><tbody>");

            for (int r = bounds.firstRow; r <= bounds.lastRow; r++) {
                Row row = sheet.getRow(r);
                float height = row == null || row.getHeightInPoints() <= 0 ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints();
                html.append("<tr style='height:").append(fmt(height)).append("pt'>");
                for (int c = bounds.firstCol; c <= bounds.lastCol; c++) {
                    if (sheet.isColumnHidden(c) || merges.covered(r, c)) continue;
                    CellRangeAddress region = merges.anchor(r, c);
                    int rowspan = region == null ? 1 : region.getLastRow() - region.getFirstRow() + 1;
                    int colspan = region == null ? 1 : region.getLastColumn() - region.getFirstColumn() + 1;
                    Cell cell = row == null ? null : row.getCell(c);
                    String text = normalizeVisibleText(display(cell, formatter, evaluator));
                    CellStyle style = cell == null ? null : cell.getCellStyle();

                    html.append("<td");
                    if (rowspan > 1) html.append(" rowspan='").append(rowspan).append("'");
                    if (colspan > 1) html.append(" colspan='").append(colspan).append("'");
                    html.append(" style='").append(cellCss(wb, cell, style)).append("'>");
                    html.append("<div class='cell-text'>").append(h(text).replace("\n", "<br>"));
                    if (text.isEmpty()) html.append("&nbsp;");
                    html.append("</div></td>");
                }
                html.append("</tr>");
            }
            html.append("</tbody></table></div></div></section>");
            return html.toString();
        }
    }

    private String normalizeVisibleText(String text) {
        String raw = safe(text).replace('\u00A0', ' ').replace('\u202F', ' ');
        if (!raw.matches("(?isu)^\\s*(?:qeyd(?:l[əe]r)?\\s*[:;,.\\-–—]*\\s*){2,}.*$")) return raw;
        String clean = ExcelUtil.cleanNotePrefix(raw);
        return clean.isBlank() ? "Qeyd:" : "Qeyd: " + clean;
    }

    private String cellCss(XSSFWorkbook wb, Cell cell, CellStyle style) {
        if (style == null) return "font:11pt 'Calibri',Arial,sans-serif;";
        StringBuilder css = new StringBuilder(180);
        Font rawFont = wb.getFontAt(style.getFontIndex());
        if (rawFont != null) {
            String name = safe(rawFont.getFontName()).replace("'", "\\'");
            css.append("font-family:'").append(name.isBlank() ? "Calibri" : name).append("',Arial,sans-serif;");
            css.append("font-size:").append(fmt(rawFont.getFontHeightInPoints())).append("pt;");
            if (rawFont.getBold()) css.append("font-weight:700;");
            if (rawFont.getItalic()) css.append("font-style:italic;");
            if (rawFont.getUnderline() != Font.U_NONE) css.append("text-decoration:underline;");
            String fc = fontColor(rawFont);
            if (fc != null) css.append("color:").append(fc).append(';');
        }

        String fill = fillColor(style);
        if (fill != null) css.append("background:").append(fill).append(';');

        HorizontalAlignment ha = style.getAlignment();
        String align = switch (ha) {
            case CENTER, CENTER_SELECTION -> "center";
            case RIGHT -> "right";
            case JUSTIFY, DISTRIBUTED -> "justify";
            case LEFT, FILL -> "left";
            default -> cell != null && cell.getCellType() == CellType.NUMERIC ? "right" : "left";
        };
        css.append("text-align:").append(align).append(';');

        VerticalAlignment va = style.getVerticalAlignment();
        css.append("vertical-align:").append(switch (va) {
            case TOP -> "top";
            case CENTER -> "middle";
            default -> "bottom";
        }).append(';');
        css.append("white-space:").append(style.getWrapText() ? "pre-wrap" : "pre-wrap").append(';');

        css.append(borderCss(style, "top"));
        css.append(borderCss(style, "right"));
        css.append(borderCss(style, "bottom"));
        css.append(borderCss(style, "left"));
        return css.toString();
    }

    private String borderCss(CellStyle style, String side) {
        BorderStyle b = switch (side) {
            case "top" -> style.getBorderTop();
            case "right" -> style.getBorderRight();
            case "bottom" -> style.getBorderBottom();
            default -> style.getBorderLeft();
        };
        if (b == null || b == BorderStyle.NONE) return "border-" + side + ":none;";
        String kind = switch (b) {
            case DASHED, MEDIUM_DASHED, SLANTED_DASH_DOT -> "dashed";
            case DOTTED, HAIR, DASH_DOT, DASH_DOT_DOT, MEDIUM_DASH_DOT, MEDIUM_DASH_DOT_DOT -> "dotted";
            case DOUBLE -> "double";
            default -> "solid";
        };
        int px = switch (b) {
            case MEDIUM, MEDIUM_DASHED, MEDIUM_DASH_DOT, MEDIUM_DASH_DOT_DOT, SLANTED_DASH_DOT -> 2;
            case THICK, DOUBLE -> 3;
            default -> 1;
        };
        String color = borderColor(style, side);
        return "border-" + side + ":" + px + "px " + kind + " " + (color == null ? "#000" : color) + ";";
    }

    private String borderColor(CellStyle style, String side) {
        if (!(style instanceof XSSFCellStyle xs)) return null;
        XSSFColor c = switch (side) {
            case "top" -> xs.getTopBorderXSSFColor();
            case "right" -> xs.getRightBorderXSSFColor();
            case "bottom" -> xs.getBottomBorderXSSFColor();
            default -> xs.getLeftBorderXSSFColor();
        };
        return color(c);
    }

    private String fontColor(Font font) {
        // Bu servis yalnız XSSFWorkbook oxuyur; onun fontları XSSFFont olur.
        // POI 5.4.x-də IndexedColors#getRGB() yoxdur və əvvəlki fallback build-i
        // sındırırdı. XSSF rəngi yoxdursa brauzer Excel-in default qara rəngini
        // istifadə etsin deyə null qaytarmaq daha düzgündür.
        if (font instanceof XSSFFont xf) return color(xf.getXSSFColor());
        return null;
    }

    private String fillColor(CellStyle style) {
        if (style.getFillPattern() == FillPatternType.NO_FILL) return null;
        if (style instanceof XSSFCellStyle xs) return color(xs.getFillForegroundXSSFColor());
        return null;
    }

    private String color(XSSFColor c) {
        if (c == null) return null;
        byte[] rgb = c.getRGB();
        if (rgb == null) rgb = c.getARGB();
        if (rgb == null || rgb.length < 3) return null;
        int off = rgb.length == 4 ? 1 : 0;
        return String.format("#%02X%02X%02X", rgb[off] & 255, rgb[off + 1] & 255, rgb[off + 2] & 255);
    }

    private String display(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        try { return formatter.formatCellValue(cell, evaluator); }
        catch (Exception e) { return formatter.formatCellValue(cell); }
    }

    private Bounds bounds(XSSFWorkbook wb, Sheet sheet) {
        int firstRow = Integer.MAX_VALUE, lastRow = -1, firstCol = Integer.MAX_VALUE, lastCol = -1;
        DataFormatter boundsFormatter = new DataFormatter(Locale.forLanguageTag("az-AZ"));
        for (Row row : sheet) {
            if (row == null) continue;
            for (Cell cell : row) {
                if (cell == null) continue;
                String text;
                try { text = boundsFormatter.formatCellValue(cell); }
                catch (Exception e) { text = ""; }
                if (safe(text).isBlank()) continue;
                firstRow = Math.min(firstRow, row.getRowNum());
                lastRow = Math.max(lastRow, row.getRowNum());
                firstCol = Math.min(firstCol, cell.getColumnIndex());
                lastCol = Math.max(lastCol, cell.getColumnIndex());
            }
        }
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress m = sheet.getMergedRegion(i);
            firstRow = Math.min(firstRow, m.getFirstRow()); lastRow = Math.max(lastRow, m.getLastRow());
            firstCol = Math.min(firstCol, m.getFirstColumn()); lastCol = Math.max(lastCol, m.getLastColumn());
        }

        int sheetIndex = wb.getSheetIndex(sheet);
        String area = wb.getPrintArea(sheetIndex);
        if (area != null && !area.isBlank()) {
            try {
                String clean = area.substring(area.indexOf('!') + 1).replace("$", "");
                String firstArea = clean.split(",")[0];
                CellRangeAddress p = CellRangeAddress.valueOf(firstArea);
                firstRow = p.getFirstRow(); lastRow = p.getLastRow(); firstCol = p.getFirstColumn(); lastCol = p.getLastColumn();
            } catch (Exception ignored) {}
        }
        if (lastRow < 0) return new Bounds(0, 0, 0, 0);
        return new Bounds(Math.max(0, firstRow), Math.max(firstRow, lastRow), Math.max(0, firstCol), Math.max(firstCol, lastCol));
    }

    private static final class MergeIndex {
        private final Map<Long, CellRangeAddress> anchors = new HashMap<>();
        private final Set<Long> covered = new HashSet<>();
        MergeIndex(Sheet sheet, Bounds bounds) {
            for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
                CellRangeAddress m = sheet.getMergedRegion(i);
                if (m.getLastRow() < bounds.firstRow || m.getFirstRow() > bounds.lastRow || m.getLastColumn() < bounds.firstCol || m.getFirstColumn() > bounds.lastCol) continue;
                anchors.put(key(m.getFirstRow(), m.getFirstColumn()), m);
                for (int r = m.getFirstRow(); r <= m.getLastRow(); r++) {
                    for (int c = m.getFirstColumn(); c <= m.getLastColumn(); c++) {
                        if (r != m.getFirstRow() || c != m.getFirstColumn()) covered.add(key(r, c));
                    }
                }
            }
        }
        CellRangeAddress anchor(int r, int c) { return anchors.get(key(r, c)); }
        boolean covered(int r, int c) { return covered.contains(key(r, c)); }
        private static long key(int r, int c) { return (((long) r) << 32) ^ (c & 0xffffffffL); }
    }

    private record Bounds(int firstRow, int lastRow, int firstCol, int lastCol) {}

    private String invoiceKey(InvoiceData inv) {
        return blank(inv.getEInvoiceNumber()) ? safe(inv.getMtNumber()) : inv.getEInvoiceNumber();
    }
    private String firstNonBlank(String first, String second) { return !blank(first) ? first.trim() : safe(second).trim(); }
    private String sanitize(String s) { return safe(s).replaceAll("[^A-Za-z0-9_-]", "_"); }
    private int clamp(int x, int lo, int hi) { return Math.max(lo, Math.min(hi, x)); }
    private boolean blank(String s) { return s == null || s.isBlank(); }
    private String safe(String s) { return s == null ? "" : s; }
    private String fmt(double x) { return String.format(Locale.ROOT, "%.2f", x); }
    private String h(String s) {
        return safe(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
