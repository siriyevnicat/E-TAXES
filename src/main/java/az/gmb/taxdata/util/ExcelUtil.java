package az.gmb.taxdata.util;

import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

public final class ExcelUtil {
    private ExcelUtil() {}

    public static String text(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        try { return formatter.formatCellValue(cell, evaluator).trim(); }
        catch (Exception e) { return formatter.formatCellValue(cell).trim(); }
    }

    public static String norm(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT)
                .replace('ə','e').replace('ı','i').replace('ö','o').replace('ü','u')
                .replace('ş','s').replace('ç','c').replace('ğ','g');
        x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return x.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Qeyd/Qeyd: kimi təkrar prefiksləri təmizləyir. */
    public static String cleanNotePrefix(String s) {
        if (s == null) return "";
        String x = s.replace('\u00A0', ' ').replace('\u202F', ' ').trim();
        // Qeyd :, qeyd:, Qeyd -, Qeyd. və s. yalnız sətrin əvvəlində silinir.
        x = x.replaceFirst("(?iu)^(?:\\s*qeyd(?:l[əe]r)?\\s*[:;,.\\-–—]*\\s*)+", "");
        return x.trim();
    }

    public static BigDecimal decimal(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        String x = s.replace("₼", "").replace("AZN", "").replace(" ", "").trim();
        if (x.matches(".*\\d,\\d{1,2}$") && x.contains(".")) x = x.replace(".", "").replace(',', '.');
        else x = x.replace(',', '.');
        x = x.replaceAll("[^0-9.\\-]", "");
        if (x.isBlank() || x.equals("-") || x.equals(".")) return BigDecimal.ZERO;
        try { return new BigDecimal(x); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    public static LocalDate date(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        String s = text(cell, formatter, evaluator);
        if (s.isBlank()) return null;
        for (var f : java.util.List.of(
                java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"),
                java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy"),
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))) {
            try { return LocalDate.parse(s, f); } catch (Exception ignored) {}
        }
        return null;
    }

    public static void setString(Sheet sheet, String ref, String value) {
        Cell cell = cell(sheet, ref);
        cell.setCellValue(value == null ? "" : value);
    }

    public static void setNumber(Sheet sheet, String ref, BigDecimal value) {
        cell(sheet, ref).setCellValue(value == null ? 0d : value.doubleValue());
    }

    public static Cell cell(Sheet sheet, String ref) {
        var a = new org.apache.poi.ss.util.CellReference(ref);
        Row row = sheet.getRow(a.getRow());
        if (row == null) row = sheet.createRow(a.getRow());
        Cell cell = row.getCell(a.getCol());
        if (cell == null) cell = row.createCell(a.getCol());
        return cell;
    }

    public static String columnName(int zeroBased) {
        int n = zeroBased + 1;
        StringBuilder b = new StringBuilder();
        while (n > 0) {
            int rem = (n - 1) % 26;
            b.append((char)('A' + rem));
            n = (n - 1) / 26;
        }
        return b.reverse().toString();
    }

    public static void copyStyleAndMergeSafe(Row templateRow, Row newRow, int maxCol) {
        if (templateRow == null || newRow == null) return;
        newRow.setHeight(templateRow.getHeight());
        for (int c = 0; c <= maxCol; c++) {
            Cell src = templateRow.getCell(c);
            Cell dst = newRow.getCell(c);
            if (dst == null) dst = newRow.createCell(c);
            if (src != null) {
                dst.setCellStyle(src.getCellStyle());
            }
        }
    }
}
