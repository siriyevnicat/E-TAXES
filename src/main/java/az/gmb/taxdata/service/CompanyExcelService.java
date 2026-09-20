package az.gmb.taxdata.service;

import az.gmb.taxdata.model.CompanyInfo;
import az.gmb.taxdata.util.ExcelUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

@Service
public class CompanyExcelService {
    // V5.3: sənəd nömrələri artıq şirkət rekvizitinin hissəsi deyil.
    // HF/QR/TT üçün bir ümumi başlanğıc nömrə generasiya ekranında idarə olunur.
    private static final List<String> ARCHIVE_HEADERS = List.of(
            "Şəxs tipi", "Rol", "Şirkət / Ad Soyad Ata adı", "Direktor / Səlahiyyətli şəxs", "VÖEN", "Ünvan",
            "Bank Adı", "Bank kodu", "Bank SWIFT", "Bank VÖEN", "Bank H/h", "Bank M/h",
            "Müqavilə №", "Müqavilə Tarixi"
    );

    private static final List<String> LEGAL_HEADERS = ARCHIVE_HEADERS;
    private static final List<String> INDIVIDUAL_HEADERS = List.of(
            "Şəxs tipi", "Rol", "Ad Soyad Ata adı", "VÖEN", "Ünvan", "Bank Adı", "Bank kodu", "Bank SWIFT",
            "Bank VÖEN", "Bank H/h", "Bank M/h", "Müqavilə №", "Müqavilə Tarixi"
    );

    private final StorageService storage;
    private final ObjectMapper mapper;

    public CompanyExcelService(StorageService storage, ObjectMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
    }

    public List<CompanyInfo> parse(Path path) throws IOException {
        try (InputStream in = java.nio.file.Files.newInputStream(path); Workbook wb = WorkbookFactory.create(in)) {
            List<CompanyInfo> result = new ArrayList<>();
            DataFormatter f = new DataFormatter();
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            for (Sheet sheet : wb) {
                HeaderMapping hm = detectHeader(sheet, f, ev);
                if (hm == null) continue;
                for (int r = hm.row + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String company = value(row, hm, "company", f, ev);
                    if (company.isBlank()) continue;
                    CompanyInfo c = new CompanyInfo();
                    c.setId("UPLOAD_" + sheet.getSheetName().replaceAll("[^A-Za-z0-9]", "_") + "_" + r);
                    c.setRole(value(row, hm, "role", f, ev));
                    c.setEntityType(value(row, hm, "entityType", f, ev));
                    c.setCompany(company);
                    c.setDirector(value(row, hm, "director", f, ev));
                    c.setVoen(value(row, hm, "voen", f, ev));
                    c.setAddress(value(row, hm, "address", f, ev));
                    c.setBankName(value(row, hm, "bankName", f, ev));
                    c.setBankCode(value(row, hm, "bankCode", f, ev));
                    c.setBankSwift(value(row, hm, "bankSwift", f, ev));
                    c.setBankVoen(value(row, hm, "bankVoen", f, ev));
                    c.setBankAccount(value(row, hm, "bankAccount", f, ev));
                    c.setCorrespondentAccount(value(row, hm, "correspondentAccount", f, ev));
                    c.setHandoverNo(value(row, hm, "handoverNo", f, ev));
                    c.setInvoiceNo(value(row, hm, "invoiceNo", f, ev));
                    c.setPriceProtocolNo(value(row, hm, "priceProtocolNo", f, ev));
                    c.setContractNo(value(row, hm, "contractNo", f, ev));
                    c.setContractDate(value(row, hm, "contractDate", f, ev));
                    if (c.getEntityType().isBlank()) c.setEntityType(CompanyInfo.normalizeEntityType("", company, c.getDirector()));
                    result.add(c);
                }
            }
            if (result.isEmpty()) throw new IllegalArgumentException("Şirkət Excel-ində 'Şirkət' və ya 'Ad Soyad Ata adı' başlığı olan cədvəl tapılmadı.");
            return result;
        }
    }

    public List<CompanyInfo> parseUpload(String workspaceId, String uploadId) throws IOException {
        return parse(storage.resolveUploadFile(workspaceId, uploadId));
    }

    public List<CompanyInfo> getDefaultCompanies() throws IOException {
        return loadDefaultCompanies();
    }

    public byte[] buildTemplateWithArchiveCompanies() throws IOException {
        List<CompanyInfo> companies = loadDefaultCompanies();
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Şirkətlər");
            CellStyle header = createHeaderStyle(wb);
            writeHeaders(s, ARCHIVE_HEADERS, header);
            int r = 1;
            for (CompanyInfo company : companies) {
                Row row = s.createRow(r++);
                Map<String, String> map = company.toMap();
                for (int i = 0; i < ARCHIVE_HEADERS.size(); i++) row.createCell(i).setCellValue(map.getOrDefault(ARCHIVE_HEADERS.get(i), ""));
            }
            finishSheet(s, ARCHIVE_HEADERS.size());
            wb.write(out);
            return out.toByteArray();
        }
    }

    public byte[] buildBlankTemplate(String entityType) throws IOException {
        boolean individual = CompanyInfo.INDIVIDUAL.equalsIgnoreCase(entityType);
        List<String> headers = individual ? INDIVIDUAL_HEADERS : LEGAL_HEADERS;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet(individual ? "Fiziki şəxs" : "Hüquqi şəxs");
            writeHeaders(s, headers, createHeaderStyle(wb));
            Row blank = s.createRow(1);
            blank.createCell(0).setCellValue(individual ? "Fiziki şəxs" : "Hüquqi şəxs");
            for (int i = 1; i < headers.size(); i++) blank.createCell(i).setCellValue("");
            finishSheet(s, headers.size());
            wb.write(out);
            return out.toByteArray();
        }
    }

    private CellStyle createHeaderStyle(XSSFWorkbook wb) {
        CellStyle header = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        header.setFont(font);
        header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        header.setWrapText(true);
        return header;
    }

    private void writeHeaders(Sheet s, List<String> headers, CellStyle headerStyle) {
        Row hr = s.createRow(0);
        hr.setHeightInPoints(30);
        for (int i = 0; i < headers.size(); i++) {
            Cell c = hr.createCell(i);
            c.setCellValue(headers.get(i));
            c.setCellStyle(headerStyle);
        }
    }

    private void finishSheet(Sheet s, int columnCount) {
        s.createFreezePane(0, 1);
        s.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, s.getLastRowNum()), 0, columnCount - 1));
        for (int i = 0; i < columnCount; i++) {
            s.autoSizeColumn(i);
            s.setColumnWidth(i, Math.min(Math.max(s.getColumnWidth(i) + 700, 3500), 15000));
        }
    }

    private List<CompanyInfo> loadDefaultCompanies() throws IOException {
        JsonNode root = mapper.readTree(new ClassPathResource("data/requisites.json").getInputStream());
        List<CompanyInfo> out = new ArrayList<>();
        addJsonCompanies(root.path("executors"), "İCRAÇI", out);
        addJsonCompanies(root.path("orderers"), "SİFARİŞÇİ", out);
        return out;
    }

    private void addJsonCompanies(JsonNode arr, String role, List<CompanyInfo> out) {
        if (!arr.isArray()) return;
        int idx = 0;
        for (JsonNode n : arr) {
            CompanyInfo c = new CompanyInfo();
            c.setId("SITE_" + role.replaceAll("[^A-Za-z0-9]", "_") + "_" + idx++);
            c.setRole(role);
            c.setCompany(txt(n, "Şirkət:"));
            c.setDirector(txt(n, "Direktor:"));
            c.setVoen(txt(n, "VÖEN:"));
            c.setEntityType(CompanyInfo.normalizeEntityType(txt(n, "Şəxs tipi:"), c.getCompany(), c.getDirector(), c.getVoen()));
            c.setAddress(txt(n, "Ünvan:"));
            c.setBankName(txt(n, "Bank Adı:"));
            c.setBankCode(txt(n, "Bank kodu:"));
            c.setBankSwift(txt(n, "Bank SWIFT:"));
            c.setBankVoen(txt(n, "Bank VÖEN:"));
            c.setBankAccount(txt(n, "Bank H/h:"));
            c.setCorrespondentAccount(txt(n, "Bank M/h:"));
            c.setHandoverNo(txt(n, "Təhvil təslim sənəd no:"));
            c.setInvoiceNo(txt(n, "Hesab faktura sənəd no:"));
            c.setPriceProtocolNo(txt(n, "Qiymət razılaşdırma sənəd no:"));
            c.setContractNo(txt(n, "Müqavilə №:"));
            c.setContractDate(txt(n, "Müqavilə Tarixi:"));
            out.add(c);
        }
    }

    private String txt(JsonNode n, String key) { return n.path(key).asText("").trim(); }

    private record HeaderMapping(int row, Map<String,Integer> cols) {}

    private HeaderMapping detectHeader(Sheet s, DataFormatter f, FormulaEvaluator ev) {
        for (int r = s.getFirstRowNum(); r <= Math.min(s.getLastRowNum(), 30); r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            Map<String,Integer> cols = new HashMap<>();
            for (Cell cell : row) {
                String n = ExcelUtil.norm(ExcelUtil.text(cell, f, ev));
                String key = headerKey(n);
                if (key != null) cols.putIfAbsent(key, cell.getColumnIndex());
            }
            if (cols.containsKey("company")) return new HeaderMapping(r, cols);
        }
        return null;
    }

    private String value(Row row, HeaderMapping hm, String key, DataFormatter f, FormulaEvaluator ev) {
        Integer col = hm.cols.get(key);
        return col == null ? "" : ExcelUtil.text(row.getCell(col), f, ev).trim();
    }

    private String headerKey(String n) {
        if (n.equals("sexs tipi") || n.equals("subyekt tipi") || n.equals("entity type") || n.equals("tip")) return "entityType";
        if (n.equals("rol") || n.equals("role")) return "role";
        if (n.equals("sirket") || n.equals("sirket adi") || n.contains("sirket ad soyad") || n.contains("ad soyad ata adi") || n.equals("ad soyad") || n.equals("company") || n.equals("company name")) return "company";
        if (n.contains("direktor") || n.contains("selahiyyetli sexs") || n.equals("director")) return "director";
        if (n.equals("voen") || n.equals("tin")) return "voen";
        if (n.contains("unvan") || n.equals("address")) return "address";
        if (n.contains("bank adi") || n.equals("bank")) return "bankName";
        if (n.contains("bank kod")) return "bankCode";
        if (n.contains("swift")) return "bankSwift";
        if (n.contains("bank voen")) return "bankVoen";
        if (n.contains("bank h h") || n.contains("hesablasma hesabi") || n.equals("iban")) return "bankAccount";
        if (n.contains("bank m h") || n.contains("muxbir hesab")) return "correspondentAccount";
        if (n.contains("tehvil") && n.contains("sened") && (n.contains("no") || n.contains("nomre"))) return "handoverNo";
        if ((n.contains("hesab faktura") || n.equals("hf no")) && (n.contains("no") || n.contains("nomre"))) return "invoiceNo";
        if (n.contains("qiymet") && n.contains("razilasdir") && (n.contains("no") || n.contains("nomre"))) return "priceProtocolNo";
        if (n.contains("muqavile") && (n.contains("no") || n.contains("nomre"))) return "contractNo";
        if (n.contains("muqavile") && n.contains("tarix")) return "contractDate";
        return null;
    }
}
