package az.gmb.taxdata.service;

import az.gmb.taxdata.model.InvoiceData;
import az.gmb.taxdata.model.InvoiceItem;
import az.gmb.taxdata.model.InvoiceSummary;
import az.gmb.taxdata.util.ExcelUtil;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

@Service
public class InvoiceExcelService {
    public static final String VAT_AUTO = "AUTO";
    public static final String VAT_EXCLUSIVE = "EXCLUSIVE_18";
    public static final String VAT_INCLUDED = "INCLUDED_18";
    public static final String VAT_NONE = "NO_VAT";
    private static final BigDecimal VAT_RATE = new BigDecimal("0.18");
    private static final BigDecimal VAT_DIVISOR = new BigDecimal("1.18");

    private final StorageService storage;

    public InvoiceExcelService(StorageService storage) { this.storage = storage; }

    public byte[] buildBlankInvoiceTemplate() throws IOException {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Qaimələr");
            String[] headers = {"E-Qaimə №", "MT", "Tarix", "Alıcı VÖEN", "Alıcı", "Satıcı VÖEN", "Satıcı", "Qeyd", "Mal / xidmət", "Vahid", "Miqdar", "Qiymət", "Məbləğ", "ƏDV məbləği", "ƏDV dərəcəsi"};
            Row h = s.createRow(0); CellStyle hs = wb.createCellStyle(); Font f = wb.createFont(); f.setBold(true); hs.setFont(f); hs.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex()); hs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (int i=0;i<headers.length;i++){ Cell c=h.createCell(i); c.setCellValue(headers[i]); c.setCellStyle(hs); s.setColumnWidth(i, i==6 ? 9000 : (i>=3&&i<=5 ? 7000 : 4300)); }
            s.createFreezePane(0,1); wb.write(out); return out.toByteArray();
        }
    }

    public InvoiceData parseUpload(String workspaceId, String uploadId, String serialNumber) throws IOException {
        return parseUpload(workspaceId, uploadId, serialNumber, VAT_AUTO);
    }

    public InvoiceData parseUpload(String workspaceId, String uploadId, String serialNumber, String vatMode) throws IOException {
        return parse(storage.resolveUploadFile(workspaceId, uploadId), serialNumber, vatMode);
    }


    /**
     * Seçilmiş çoxlu qaimələri eyni Excel faylını yalnız BİR DƏFƏ açaraq oxuyur.
     * Standart e-taxes ixraclarında bu, hər qaimə üçün workbook-u yenidən açmaqdan
     * qat-qat sürətlidir. Standart cədvəldə tapılmayan MT-lər köhnə parserə fallback edir.
     */
    public Map<String, InvoiceData> parseManyUpload(String workspaceId, String uploadId, Collection<String> serialNumbers) throws IOException {
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        if (serialNumbers != null) for (String x : serialNumbers) { String mt=normalizeSerial(x); if (!mt.isBlank()) wanted.add(mt); }
        LinkedHashMap<String,InvoiceData> out = new LinkedHashMap<>();
        if (wanted.isEmpty()) return out;
        Path path = storage.resolveUploadFile(workspaceId, uploadId);
        try (InputStream in = Files.newInputStream(path); Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter f = new DataFormatter(); FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            Map<String, LinkedHashSet<String>> notes = new HashMap<>();
            Map<String, LinkedHashSet<String>> invoiceNos = new HashMap<>();
            Map<String, LinkedHashSet<String>> sheets = new HashMap<>();
            for (Sheet sh : wb) {
                TableHeader h = detectTableHeader(sh, f, ev);
                if (h == null || !h.cols.containsKey("serial")) continue;
                int serialCol=h.cols.get("serial");
                for (int r=h.row+1;r<=sh.getLastRowNum();r++) {
                    Row row=sh.getRow(r); if(row==null)continue;
                    String mt=normalizeSerial(ExcelUtil.text(row.getCell(serialCol),f,ev));
                    if(!wanted.contains(mt))continue;
                    InvoiceData d=out.computeIfAbsent(mt,k->{InvoiceData z=new InvoiceData();z.setMtNumber(k);return z;});
                    if(d.getInvoiceDate()==null)d.setInvoiceDate(getDate(row,h,"date",f,ev));
                    if(blank(d.getBuyerName()))d.setBuyerName(get(row,h,"buyer",f,ev));
                    if(blank(d.getBuyerVoen()))d.setBuyerVoen(get(row,h,"buyerVoen",f,ev));
                    if(blank(d.getSellerName()))d.setSellerName(get(row,h,"seller",f,ev));
                    if(blank(d.getSellerVoen()))d.setSellerVoen(get(row,h,"sellerVoen",f,ev));
                    String eno=get(row,h,"eInvoiceNo",f,ev).trim(); if(!eno.isBlank())invoiceNos.computeIfAbsent(mt,k->new LinkedHashSet<>()).add(eno);
                    String note=ExcelUtil.cleanNotePrefix(get(row,h,"note",f,ev)); if(!note.isBlank())notes.computeIfAbsent(mt,k->new LinkedHashSet<>()).add(note);
                    String name=get(row,h,"name",f,ev); BigDecimal qty=dec(row,h,"qty",f,ev), price=dec(row,h,"price",f,ev), amount=dec(row,h,"amount",f,ev);
                    if(amount.signum()==0&&qty.signum()!=0&&price.signum()!=0)amount=qty.multiply(price);
                    if(!name.isBlank()||amount.signum()!=0)d.getItems().add(new InvoiceItem(name.isBlank()?"Ehtiyat hissələri və xidmət":name,blankTo(get(row,h,"unit",f,ev),"ədəd"),qty.signum()==0?BigDecimal.ONE:qty,price,amount));
                    BigDecimal vat=vatForRow(row,h,amount,f,ev); if(vat.signum()>0)d.setVat(d.getVat().add(vat));
                    sheets.computeIfAbsent(mt,k->new LinkedHashSet<>()).add(sh.getSheetName());
                }
            }
            for(String mt:new ArrayList<>(out.keySet())){
                InvoiceData d=out.get(mt); d.setEInvoiceNumber(String.join(" / ",invoiceNos.getOrDefault(mt,new LinkedHashSet<>())));
                d.setNote(String.join(" | ",notes.getOrDefault(mt,new LinkedHashSet<>()))); d.setSourceSheet(String.join(", ",sheets.getOrDefault(mt,new LinkedHashSet<>())));
                d.setParserNote("Sürətli batch parser: Excel bir dəfə açıldı və seçilmiş MT-lər eyni keçiddə oxundu."); out.put(mt,finalizeTotals(d,VAT_AUTO));
            }
        }
        // qeyri-standart fayl formatları üçün tam köhnə parseri yalnız tapılmayan MT-lərə tətbiq et
        for(String mt:wanted) if(!out.containsKey(mt)) out.put(mt,parse(path,mt,VAT_AUTO));
        return out;
    }

    public List<String> findMtNumbers(String workspaceId, String uploadId, String query, int limit) throws IOException {
        Path path = storage.resolveUploadFile(workspaceId, uploadId);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String q = query == null ? "" : normalizeSerial(query);
        try (InputStream in = Files.newInputStream(path); Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter f = new DataFormatter();
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();

            // Əsas seçim yalnız MT/seriya sütununa görə edilir. E-qaimə nömrəsi ayrıca sahədir.
            for (Sheet s : wb) {
                TableHeader h = detectTableHeader(s, f, ev);
                if (h == null || !h.cols.containsKey("serial")) continue;
                int col = h.cols.get("serial");
                for (int r = h.row + 1; r <= s.getLastRowNum(); r++) {
                    Row row = s.getRow(r);
                    if (row == null) continue;
                    String serial = extractSerial(ExcelUtil.text(row.getCell(col), f, ev));
                    if (!serial.isBlank() && (q.isBlank() || serial.contains(q)) && out.add(serial) && out.size() >= limit)
                        return new ArrayList<>(out);
                }
            }

            // Sütunlu format tapılmasa, iş kitabında MT kodlarını ehtiyat variant kimi axtar.
            if (out.isEmpty()) {
                for (Sheet s : wb) {
                    for (Row row : s) {
                        for (Cell c : row) {
                            String serial = extractSerial(ExcelUtil.text(c, f, ev));
                            if (!serial.isBlank() && (q.isBlank() || serial.contains(q)) && out.add(serial) && out.size() >= limit)
                                return new ArrayList<>(out);
                        }
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }


    public List<InvoiceSummary> listInvoices(String workspaceId, String uploadId, String vatMode, int limit) throws IOException {
        Path path = storage.resolveUploadFile(workspaceId, uploadId);
        LinkedHashMap<String, InvoiceSummary> found = new LinkedHashMap<>();

        try (InputStream in = Files.newInputStream(path); Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter f = new DataFormatter();
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();

            // V5.2: Bu mərhələdə MT axtarılmır. Məqsəd yalnız qaimə siyahısını
            // e-taxes məntiqində göstərməkdir. Qaimə cədvəlinin "Qaimə № / E-Qaimə №"
            // sütunu tapılır və hər qaimə bir siyahı sətri kimi hazırlanır.
            for (Sheet s : wb) {
                for (TableHeader h : detectInvoiceListHeaders(s, f, ev)) {
                    boolean detailRows = h.cols().containsKey("name") || h.cols().containsKey("qty") || h.cols().containsKey("price");
                    int emptyAfterData = 0;
                    boolean dataStarted = false;
                    for (int r = h.row() + 1; r <= s.getLastRowNum(); r++) {
                        Row row = s.getRow(r);
                        if (row == null) {
                            if (dataStarted && ++emptyAfterData >= 30) break;
                            continue;
                        }

                        String invoiceNo = invoiceListNumber(row, h, f, ev);
                        if (invoiceNo.isBlank()) {
                            if (dataStarted && ++emptyAfterData >= 30) break;
                            continue;
                        }
                        dataStarted = true;
                        emptyAfterData = 0;

                        // Başlıq və cəmi kimi texniki sətirləri qaimə kimi göstərmə.
                        String normalized = ExcelUtil.norm(invoiceNo);
                        if (isEInvoiceLabel(normalized) || normalized.equals("cemi") || normalized.equals("cem") || normalized.contains("yekun")) continue;

                        String mt = extractSerial(invoiceNo);
                        if (mt.isBlank() && h.cols().containsKey("serial")) mt = extractSerial(get(row, h, "serial", f, ev));
                        if (mt.isBlank()) {
                            // MT ayrıca sütun kimi varsa həmin sətrin içindən metadata kimi götürülür;
                            // bu, MT üzrə bütün workbook-u axtarmaq deyil.
                            for (Cell c : row) {
                                mt = extractSerial(ExcelUtil.text(c, f, ev));
                                if (!mt.isBlank()) break;
                            }
                        }

                        LocalDate date = getDate(row, h, "date", f, ev);
                        String buyer = get(row, h, "buyer", f, ev).trim();
                        String buyerVoen = get(row, h, "buyerVoen", f, ev).trim();
                        String seller = get(row, h, "seller", f, ev).trim();
                        String sellerVoen = get(row, h, "sellerVoen", f, ev).trim();
                        String note = ExcelUtil.cleanNotePrefix(get(row, h, "note", f, ev));
                        BigDecimal amount = dec(row, h, "amount", f, ev).setScale(2, RoundingMode.HALF_UP);
                        BigDecimal vat = vatForRow(row, h, amount, f, ev).setScale(2, RoundingMode.HALF_UP);
                        BigDecimal total = amount.signum() == 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : amount;

                        String key = normalizeInvoiceNumber(invoiceNo);
                        InvoiceSummary current = found.get(key);
                        if (current == null) {
                            found.put(key, new InvoiceSummary(mt, invoiceNo, date, buyer, buyerVoen, seller, sellerVoen, detailRows ? 1 : 0,
                                    amount, vat, total, vat.signum() > 0 ? "+18% (ƏDV)" : "", note, s.getSheetName()));
                        } else {
                            // Eyni qaimə detal sətirlərində təkrar olunarsa bir dəfə göstər.
                            String mergedMt = blank(current.mtNumber()) ? mt : current.mtNumber();
                            LocalDate mergedDate = current.invoiceDate() != null ? current.invoiceDate() : date;
                            String mergedBuyer = blank(current.buyerName()) ? buyer : current.buyerName();
                            String mergedBuyerVoen = blank(current.buyerVoen()) ? buyerVoen : current.buyerVoen();
                            String mergedSeller = blank(current.sellerName()) ? seller : current.sellerName();
                            String mergedSellerVoen = blank(current.sellerVoen()) ? sellerVoen : current.sellerVoen();
                            String mergedNote = mergeUniqueText(current.note(), note, " | ");
                            BigDecimal mergedSubtotal = detailRows ? current.subtotal().add(amount) : (current.subtotal().signum() != 0 ? current.subtotal() : amount);
                            BigDecimal mergedVat = detailRows ? current.vat().add(vat) : (current.vat().signum() != 0 ? current.vat() : vat);
                            BigDecimal mergedAmount = detailRows ? current.total().add(total) : (current.total().signum() != 0 ? current.total() : total);
                            int mergedItemCount = current.itemCount() + (detailRows ? 1 : 0);
                            found.put(key, new InvoiceSummary(mergedMt, current.eInvoiceNumber(), mergedDate, mergedBuyer, mergedBuyerVoen, mergedSeller, mergedSellerVoen,
                                    mergedItemCount, mergedSubtotal, mergedVat, mergedAmount,
                                    mergedVat.signum() > 0 ? "+18% (ƏDV)" : current.vatLabel(), mergedNote,
                                    mergeUniqueText(current.sourceSheet(), s.getSheetName(), ", ")));
                        }

                        if (found.size() >= limit) break;
                    }
                    if (found.size() >= limit) break;
                }
                if (found.size() >= limit) break;
            }

            // Cədvəl tipli qaimə siyahısı yoxdursa, yalnız "E-Qaimə № / Qaimə №" label-larını
            // axtarırıq. Burada da MT ilə başlayan kodlara görə workbook skanı edilmir.
            if (found.isEmpty()) scanInvoiceLabels(wb, found, f, ev, limit);
        }

        return new ArrayList<>(found.values());
    }

    public InvoiceData parseUploadByInvoiceNumber(String workspaceId, String uploadId, String invoiceNumber, String vatMode) throws IOException {
        String mt = resolveMtForInvoiceNumber(workspaceId, uploadId, invoiceNumber);
        InvoiceData data = parseUpload(workspaceId, uploadId, mt, vatMode);
        if (blank(data.getEInvoiceNumber()) || data.getEInvoiceNumber().equalsIgnoreCase(data.getMtNumber())) {
            data.setEInvoiceNumber(cleanInvoiceNumber(invoiceNumber));
        }
        return data;
    }

    public String resolveMtForInvoiceNumber(String workspaceId, String uploadId, String invoiceNumber) throws IOException {
        String wanted = normalizeInvoiceNumber(invoiceNumber);
        if (wanted.isBlank()) throw new IllegalArgumentException("Qaimə nömrəsi boş ola bilməz.");
        if (wanted.matches("MT[A-Z0-9_-]{6,}")) return normalizeSerial(wanted);

        Path path = storage.resolveUploadFile(workspaceId, uploadId);
        try (InputStream in = Files.newInputStream(path); Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter f = new DataFormatter();
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();

            for (Sheet s : wb) {
                for (TableHeader h : detectInvoiceListHeaders(s, f, ev)) {
                    for (int r = h.row() + 1; r <= s.getLastRowNum(); r++) {
                        Row row = s.getRow(r);
                        if (row == null) continue;
                        String no = invoiceListNumber(row, h, f, ev);
                        if (!normalizeInvoiceNumber(no).equals(wanted)) continue;

                        String mt = extractSerial(no);
                        if (mt.isBlank() && h.cols().containsKey("serial")) mt = extractSerial(get(row, h, "serial", f, ev));
                        if (mt.isBlank()) {
                            for (Cell c : row) {
                                mt = extractSerial(ExcelUtil.text(c, f, ev));
                                if (!mt.isBlank()) break;
                            }
                        }
                        if (!mt.isBlank()) return mt;

                        // Bəzi ixraclarda qaimə nömrəsi yalnız ilk sətirdə, MT isə növbəti detal sətrində olur.
                        for (int rr = r + 1; rr <= Math.min(s.getLastRowNum(), r + 20); rr++) {
                            Row next = s.getRow(rr); if (next == null) continue;
                            String nextNo = invoiceListNumber(next, h, f, ev);
                            if (!nextNo.isBlank() && !normalizeInvoiceNumber(nextNo).equals(wanted)) break;
                            if (h.cols().containsKey("serial")) mt = extractSerial(get(next, h, "serial", f, ev));
                            if (mt.isBlank()) for (Cell c : next) { mt = extractSerial(ExcelUtil.text(c, f, ev)); if (!mt.isBlank()) break; }
                            if (!mt.isBlank()) return mt;
                        }
                    }
                }
            }

            // Yalnız alt sənəd yaratma mərhələsində fallback: seçilmiş qaimə nömrəsini tapıb
            // həmin sətrin yaxınlığında ona aid MT-ni müəyyən et.
            for (Sheet s : wb) {
                for (Row row : s) {
                    for (Cell c : row) {
                        String raw = ExcelUtil.text(c, f, ev);
                        if (!normalizeInvoiceNumber(raw).equals(wanted)) continue;
                        for (int rr = Math.max(s.getFirstRowNum(), row.getRowNum() - 3); rr <= Math.min(s.getLastRowNum(), row.getRowNum() + 12); rr++) {
                            Row near = s.getRow(rr); if (near == null) continue;
                            for (Cell nc : near) {
                                String mt = extractSerial(ExcelUtil.text(nc, f, ev));
                                if (!mt.isBlank()) return mt;
                            }
                        }
                    }
                }
            }
        }
        throw new IllegalArgumentException("Seçilmiş " + cleanInvoiceNumber(invoiceNumber) + " qaiməsi üçün MT nömrəsi tapılmadı.");
    }

    private List<TableHeader> detectInvoiceListHeaders(Sheet s, DataFormatter f, FormulaEvaluator ev) {
        List<TableHeader> out = new ArrayList<>();
        for (int r = s.getFirstRowNum(); r <= Math.min(s.getLastRowNum(), s.getFirstRowNum() + 350); r++) {
            Row row = s.getRow(r); if (row == null) continue;
            Map<String,Integer> m = headerMapFromRow(row, f, ev);
            boolean hasInvoiceNumber = m.containsKey("eInvoiceNo");
            boolean hasSerialNumber = m.containsKey("serial");
            if (!hasInvoiceNumber && !hasSerialNumber) continue;

            int listContext = 0;
            for (String k : List.of("date","buyer","seller","note","status","type")) if (m.containsKey(k)) listContext++;
            // Ayrı E-Qaimə/Qaimə № sütunu varsa məbləğ də kifayət qədər kontekstdir.
            // "Seriya və nömrəsi" həm e-taxes siyahısında, həm də MT-si hər mal sətrində olan detal cədvəlində ola bilər.
            // Hər iki struktur burada yalnız qaimələri qruplaşdırmaq üçün oxunur; mal parseri çağırılmır.
            if (hasInvoiceNumber && (listContext >= 1 || m.containsKey("amount") || m.containsKey("name"))) out.add(new TableHeader(r, m));
            else if (hasSerialNumber && (listContext >= 1 || m.containsKey("name") || m.containsKey("amount"))) out.add(new TableHeader(r, m));
        }
        return out;
    }

    private String invoiceListNumber(Row row, TableHeader h, DataFormatter f, FormulaEvaluator ev) {
        String no = h.cols().containsKey("eInvoiceNo") ? cleanInvoiceNumber(get(row, h, "eInvoiceNo", f, ev)) : "";
        if (!no.isBlank()) return no;
        if (h.cols().containsKey("serial")) {
            String serial = get(row, h, "serial", f, ev).trim();
            String number = h.cols().containsKey("numberPart") ? get(row, h, "numberPart", f, ev).trim() : "";
            String combined = (serial + number).replaceAll("\\s+", "");
            String mt = extractSerial(combined);
            if (!mt.isBlank()) return mt;
            mt = extractSerial(serial);
            if (!mt.isBlank()) return mt;
            return cleanInvoiceNumber(number.isBlank() ? serial : combined);
        }
        return "";
    }

    private void scanInvoiceLabels(Workbook wb, LinkedHashMap<String, InvoiceSummary> found,
                                   DataFormatter f, FormulaEvaluator ev, int limit) {
        for (Sheet s : wb) {
            int lastRow = Math.min(s.getLastRowNum(), s.getFirstRowNum() + 350);
            for (int r = s.getFirstRowNum(); r <= lastRow; r++) {
                Row row = s.getRow(r);
                if (row == null) continue;
                int last = Math.min(Math.max(row.getLastCellNum(), 0), 80);
                for (int c = 0; c < last; c++) {
                    String label = ExcelUtil.norm(ExcelUtil.text(row.getCell(c), f, ev));
                    if (!isEInvoiceLabel(label)) continue;
                    String no = cleanInvoiceNumber(ExcelUtil.text(row.getCell(c + 1), f, ev));
                    if (no.isBlank()) continue;
                    String key = normalizeInvoiceNumber(no);
                    if (found.containsKey(key)) continue;
                    InvoiceData meta = new InvoiceData(); meta.setEInvoiceNumber(no); meta.setSourceSheet(s.getSheetName());
                    enrichFromNearbyLabels(meta, s, row.getRowNum(), f, ev);
                    found.put(key, new InvoiceSummary("", no, meta.getInvoiceDate(), meta.getBuyerName(), meta.getBuyerVoen(), meta.getSellerName(), meta.getSellerVoen(), 0,
                            BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), "", meta.getNote(), s.getSheetName()));
                    if (found.size() >= limit) return;
                }
            }
        }
    }

    private String cleanInvoiceNumber(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)^\\s*(e[-‑–— ]?qaim[əe]|elektron\\s+qaim[əe]|qaim[əe])\\s*(№|no|nomr[əe]si)?\\s*[:#-]?\\s*", "").trim();
    }

    private String normalizeInvoiceNumber(String value) {
        return cleanInvoiceNumber(value).toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private void scanRecognizedTables(Workbook wb, LinkedHashMap<String, InvoiceData> grouped,
                                      DataFormatter f, FormulaEvaluator ev) {
        for (Sheet s : wb) {
            TableHeader h = detectTableHeader(s, f, ev);
            if (h == null || !h.cols.containsKey("serial")) continue;
            for (int r = h.row + 1; r <= s.getLastRowNum(); r++) {
                Row row = s.getRow(r);
                if (row == null) continue;
                String mt = extractSerial(get(row, h, "serial", f, ev));
                if (mt.isBlank()) continue;
                InvoiceData d = grouped.computeIfAbsent(mt, key -> {
                    InvoiceData x = new InvoiceData(); x.setMtNumber(key); x.setSourceSheet(s.getSheetName()); return x;
                });
                mergeWithoutDuplicateItems(d, parseDataRow(row, h, mt, s.getSheetName(), f, ev));
            }
        }
    }

    private List<SerialOccurrence> scanSerialOccurrences(Workbook wb, DataFormatter f, FormulaEvaluator ev, int maxOccurrences) {
        List<SerialOccurrence> out = new ArrayList<>();
        Set<String> seenRowMt = new HashSet<>();
        for (Sheet s : wb) {
            for (Row row : s) {
                if (row == null) continue;
                for (Cell c : row) {
                    String raw = ExcelUtil.text(c, f, ev);
                    String mt = extractSerial(raw);
                    if (mt.isBlank()) continue;
                    // Eyni sətirdə MT bir neçə hüceyrədə təkrar yazılıbsa bir dəfə götür.
                    String rowKey = s.getSheetName() + "#" + row.getRowNum() + "#" + mt;
                    if (!seenRowMt.add(rowKey)) continue;
                    out.add(new SerialOccurrence(s.getSheetName(), row.getRowNum(), c.getColumnIndex(), mt));
                    if (out.size() >= maxOccurrences) return out;
                }
            }
        }
        return out;
    }

    private Map<String, List<SerialOccurrence>> occurrencesBySheetAndColumn(List<SerialOccurrence> occurrences) {
        Map<String, List<SerialOccurrence>> out = new HashMap<>();
        for (SerialOccurrence o : occurrences) out.computeIfAbsent(o.sheetName() + "#" + o.col(), k -> new ArrayList<>()).add(o);
        for (List<SerialOccurrence> list : out.values()) list.sort(Comparator.comparingInt(SerialOccurrence::row));
        return out;
    }

    private InvoiceData parseOccurrenceSegment(Sheet s, SerialOccurrence occ, List<SerialOccurrence> sameColumn,
                                               DataFormatter f, FormulaEvaluator ev) {
        InvoiceData d = new InvoiceData();
        d.setMtNumber(occ.mt()); d.setSourceSheet(s.getSheetName());

        TableHeader base = bestHeaderAbove(s, occ.row(), f, ev);
        if (base == null) {
            // MT siyahıda yenə görünməlidir; məlumatı yalnız yaxın label-lardan zənginləşdiririk.
            enrichFromNearbyLabels(d, s, occ.row(), f, ev);
            d.setParserNote("MT tapıldı, lakin mal cədvəlinin başlığı avtomatik tanınmadı.");
            return d;
        }

        Map<String,Integer> cols = new HashMap<>(base.cols());
        // Başlıq MT sütununu başqa adla veribsə belə, faktiki MT-nin yerləşdiyi sütun əsasdır.
        cols.put("serial", occ.col());
        TableHeader h = new TableHeader(base.row(), cols);

        int end = Math.min(s.getLastRowNum(), occ.row() + 500);
        for (SerialOccurrence next : sameColumn) {
            if (next.row() <= occ.row()) continue;
            // Növbəti MT occurrence-ə qədər olan blank-seriyalı sətirlər cari qaiməyə aiddir.
            // MT hər mal sətrində təkrar olunursa hər occurrence yalnız öz sətrini oxuyur; beləliklə
            // eyni malın iki real sətri səhvən birləşdirilmir.
            end = Math.min(end, next.row() - 1);
            break;
        }

        // Eyni MT aşağıdakı sətirlərdə də təkrar olunursa, həmin sətirləri ayrıca occurrence kimi
        // görəcəyik. Burada isə blank MT ilə davam edən mal sətirlərini də itirməmək üçün segment oxunur.
        int emptyItemRows = 0;
        for (int r = occ.row(); r <= end; r++) {
            Row row = s.getRow(r);
            if (row == null) { if (++emptyItemRows >= 8) break; continue; }
            String rowMt = extractSerial(ExcelUtil.text(row.getCell(occ.col()), f, ev));
            if (!rowMt.isBlank() && !rowMt.equals(occ.mt())) break;

            InvoiceData rowData = parseDataRow(row, h, occ.mt(), s.getSheetName(), f, ev);
            if (rowData.getItems().isEmpty() && blank(rowData.getEInvoiceNumber()) && blank(rowData.getNote()) &&
                    rowData.getInvoiceDate() == null && blank(rowData.getBuyerName()) && blank(rowData.getSellerName())) {
                emptyItemRows++;
            } else {
                emptyItemRows = 0;
                mergeWithoutDuplicateItems(d, rowData);
            }
            if (emptyItemRows >= 8) break;
        }

        enrichFromNearbyLabels(d, s, occ.row(), f, ev);
        d.setParserNote(d.getItems().isEmpty()
                ? "MT tapıldı; başlıq tanındı, lakin mal sətrləri boş/uyğunsuz göründü."
                : "MT-nin olduğu sütun və yuxarıdakı cədvəl başlığı əsasında mal sətrləri oxundu.");
        return d;
    }

    private InvoiceData parseDataRow(Row row, TableHeader h, String mt, String sheetName,
                                     DataFormatter f, FormulaEvaluator ev) {
        InvoiceData d = new InvoiceData(); d.setMtNumber(mt); d.setSourceSheet(sheetName);
        d.setInvoiceDate(getDate(row, h, "date", f, ev));
        d.setBuyerName(get(row, h, "buyer", f, ev));
        d.setSellerName(get(row, h, "seller", f, ev));
        d.setEInvoiceNumber(get(row, h, "eInvoiceNo", f, ev).trim());
        d.setNote(ExcelUtil.cleanNotePrefix(get(row, h, "note", f, ev)));

        String name = get(row, h, "name", f, ev).trim();
        BigDecimal qty = dec(row, h, "qty", f, ev);
        BigDecimal price = dec(row, h, "price", f, ev);
        BigDecimal amount = dec(row, h, "amount", f, ev);
        if (amount.signum() == 0 && qty.signum() != 0 && price.signum() != 0) amount = qty.multiply(price);

        String nn = ExcelUtil.norm(name);
        boolean totalRow = nn.equals("cemi") || nn.equals("cem") || nn.contains("yekun") || nn.contains("toplam") ||
                (nn.equals("edv") || nn.startsWith("edv "));
        if (!totalRow && (!name.isBlank() || amount.signum() != 0 || (qty.signum() != 0 && price.signum() != 0))) {
            d.getItems().add(new InvoiceItem(name.isBlank() ? "Ehtiyat hissələri və xidmət" : name,
                    blankTo(get(row, h, "unit", f, ev), "ədəd"), qty.signum() == 0 ? BigDecimal.ONE : qty, price, amount));
        }
        BigDecimal vat = vatForRow(row, h, amount, f, ev);
        if (vat.signum() > 0) d.setVat(vat);
        return d;
    }

    private TableHeader bestHeaderAbove(Sheet s, int dataRow, DataFormatter f, FormulaEvaluator ev) {
        TableHeader best = null; int bestScore = -1;
        int first = s.getFirstRowNum();
        // Ən çox rast gəlinən iki vəziyyəti ucuz şəkildə yoxla:
        // 1) başlıq MT sətrinə yaxındır; 2) uzun cədvəldə başlıq vərəqin əvvəlindədir.
        int[][] ranges = {
                {Math.max(first, dataRow - 250), dataRow - 1},
                {first, Math.min(dataRow - 1, first + 250)}
        };
        Set<Integer> visited = new HashSet<>();
        for (int[] range : ranges) {
            for (int r = range[0]; r <= range[1]; r++) {
                if (r < first || r >= dataRow || !visited.add(r)) continue;
                Row row = s.getRow(r); if (row == null) continue;
                Map<String,Integer> m = headerMapFromRow(row, f, ev);
                if (m.isEmpty()) continue;
                int score = headerScore(m);
                if (!(m.containsKey("name") || m.containsKey("amount") || m.containsKey("price"))) continue;
                if (score > bestScore || (score == bestScore && best != null && r > best.row())) {
                    best = new TableHeader(r, m); bestScore = score;
                }
            }
        }
        return bestScore >= 2 ? best : null;
    }

    private int headerScore(Map<String,Integer> m) {
        int score = m.size();
        if (m.containsKey("name")) score += 4;
        if (m.containsKey("amount")) score += 3;
        if (m.containsKey("price")) score += 2;
        if (m.containsKey("qty")) score += 2;
        if (m.containsKey("serial")) score += 2;
        return score;
    }

    private Map<String,Integer> headerMapFromRow(Row row, DataFormatter f, FormulaEvaluator ev) {
        Map<String,Integer> m = new HashMap<>();
        for (Cell c : row) {
            String k = headerKey(ExcelUtil.norm(ExcelUtil.text(c, f, ev)));
            if (k != null) m.putIfAbsent(k, c.getColumnIndex());
        }
        return m;
    }

    private void enrichFromNearbyLabels(InvoiceData d, Sheet s, int centerRow, DataFormatter f, FormulaEvaluator ev) {
        int from = Math.max(s.getFirstRowNum(), centerRow - 40), to = Math.min(s.getLastRowNum(), centerRow + 20);
        for (int r = from; r <= to; r++) {
            Row row = s.getRow(r); if (row == null) continue;
            int last = Math.min(Math.max(row.getLastCellNum(), 0), 80);
            for (int c = 0; c < last; c++) {
                String n = ExcelUtil.norm(ExcelUtil.text(row.getCell(c), f, ev));
                if (n.isBlank()) continue;
                if (d.getInvoiceDate() == null && n.contains("tarix")) {
                    LocalDate dt = ExcelUtil.date(row.getCell(c + 1), f, ev); if (dt != null) d.setInvoiceDate(dt);
                }
                boolean taxLabel = n.contains("voen") || n.contains("tin") || n.contains("vergi");
                if (!taxLabel && blank(d.getBuyerName()) && (n.contains("alici") || n.contains("mal alan") || n.contains("malalan") || n.contains("musteri") || n.contains("sifarisci") || n.contains("qebul eden")))
                    d.setBuyerName(ExcelUtil.text(row.getCell(c + 1), f, ev));
                if (!taxLabel && blank(d.getSellerName()) && (n.contains("satici") || n.contains("mal gonderen") || n.contains("malgonderen") || n.contains("tehcizatci") || n.contains("icraci") || n.contains("gonderen")))
                    d.setSellerName(ExcelUtil.text(row.getCell(c + 1), f, ev));
                if (blank(d.getEInvoiceNumber()) && isEInvoiceLabel(n)) d.setEInvoiceNumber(ExcelUtil.text(row.getCell(c + 1), f, ev));
                if (blank(d.getNote()) && isNoteLabel(n)) d.setNote(ExcelUtil.cleanNotePrefix(ExcelUtil.text(row.getCell(c + 1), f, ev)));
            }
        }
    }

    private void mergeWithoutDuplicateItems(InvoiceData target, InvoiceData part) {
        if (part == null) return;
        if (target.getInvoiceDate() == null) target.setInvoiceDate(part.getInvoiceDate());
        if (blank(target.getBuyerName())) target.setBuyerName(part.getBuyerName());
        if (blank(target.getBuyerVoen())) target.setBuyerVoen(part.getBuyerVoen());
        if (blank(target.getSellerName())) target.setSellerName(part.getSellerName());
        if (blank(target.getSellerVoen())) target.setSellerVoen(part.getSellerVoen());
        target.setEInvoiceNumber(mergeUniqueText(target.getEInvoiceNumber(), part.getEInvoiceNumber(), " / "));
        target.setNote(mergeUniqueText(target.getNote(), part.getNote(), " | "));
        target.getItems().addAll(part.getItems());
        if (part.getVat() != null && part.getVat().signum() > 0) target.setVat(target.getVat().add(part.getVat()));
    }

    private record SerialOccurrence(String sheetName, int row, int col, String mt) {}

    private String extractSerial(String text) {
        if (text == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\bMT[A-Z0-9_-]{6,}\\b").matcher(text);
        return m.find() ? normalizeSerial(m.group()) : "";
    }

    public InvoiceData parse(Path path, String serialRaw, String vatMode) throws IOException {
        String mt = normalizeSerial(serialRaw);
        if (mt.isBlank()) throw new IllegalArgumentException("Qaimənin MT seriya nömrəsi boş ola bilməz.");
        if (!mt.matches("MT[A-Z0-9_-]{6,}"))
            throw new IllegalArgumentException("Qaimənin MT seriya nömrəsi MT ilə başlamalıdır. Məsələn: MT260810017067");

        try (InputStream in = Files.newInputStream(path); Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter f = new DataFormatter();
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            InvoiceData merged = new InvoiceData();
            merged.setMtNumber(mt);
            List<String> sourceSheets = new ArrayList<>();

            for (Sheet s : wb) {
                TableHeader h = detectTableHeader(s, f, ev);
                if (h == null || !h.cols.containsKey("serial")) continue;
                InvoiceData part = parseTable(s, h, mt, f, ev);
                if (part.getItems().isEmpty()) continue;
                merge(merged, part);
                sourceSheets.add(s.getSheetName());
            }

            // V5.1 ehtiyat variantı: MT başlığı standart adla tanınmasa belə, Excel-in bütün
            // hüceyrələrində MT-ni tap, faktiki MT sütununu və onun yuxarısındakı cədvəl başlığını
            // əsas götür. Siyahıda görünən MT ilə sənəd yaratma eyni parserdən istifadə edir.
            if (merged.getItems().isEmpty()) {
                List<SerialOccurrence> all = scanSerialOccurrences(wb, f, ev, 20000);
                List<SerialOccurrence> matches = all.stream().filter(x -> x.mt().equals(mt)).toList();
                Map<String, List<SerialOccurrence>> byColumn = occurrencesBySheetAndColumn(all);
                for (SerialOccurrence occ : matches) {
                    Sheet s = wb.getSheet(occ.sheetName());
                    InvoiceData part = parseOccurrenceSegment(s, occ,
                            byColumn.getOrDefault(occ.sheetName() + "#" + occ.col(), List.of()), f, ev);
                    if (part.getItems().isEmpty()) {
                        // Meta məlumatları yenə saxla; növbəti occurrence mal sətirlərini tapa bilər.
                        if (merged.getInvoiceDate() == null) merged.setInvoiceDate(part.getInvoiceDate());
                        if (blank(merged.getBuyerName())) merged.setBuyerName(part.getBuyerName());
                        if (blank(merged.getSellerName())) merged.setSellerName(part.getSellerName());
                        merged.setEInvoiceNumber(mergeUniqueText(merged.getEInvoiceNumber(), part.getEInvoiceNumber(), " / "));
                        merged.setNote(mergeUniqueText(merged.getNote(), part.getNote(), " | "));
                        continue;
                    }
                    mergeWithoutDuplicateItems(merged, part);
                    sourceSheets.add(s.getSheetName());
                }
            }

            // Son ehtiyat variant: ayrıca blok tipli qaimə faylları.
            if (merged.getItems().isEmpty()) {
                for (Sheet s : wb) {
                    CellPos pos = findExactOrContaining(s, mt, f, ev);
                    if (pos == null) continue;
                    InvoiceData part = parseBlock(s, pos, mt, f, ev);
                    if (part.getItems().isEmpty()) continue;
                    mergeWithoutDuplicateItems(merged, part);
                    sourceSheets.add(s.getSheetName());
                }
            }

            if (!merged.getItems().isEmpty()) {
                merged.setSourceSheet(String.join(", ", new LinkedHashSet<>(sourceSheets)));
                merged.setParserNote("MT seriya nömrəsinə dəqiq uyğun gələn məlumatlar oxundu; E-qaimə nömrəsi və Qeyd ayrıca götürüldü.");
                return finalizeTotals(merged, vatMode);
            }

            throw new IllegalArgumentException("Excel-də " + mt + " MT seriya nömrəsinə uyğun mal/xidmət sətri tapılmadı.");
        }
    }

    private void merge(InvoiceData target, InvoiceData part) {
        if (target.getInvoiceDate() == null) target.setInvoiceDate(part.getInvoiceDate());
        if (blank(target.getBuyerName())) target.setBuyerName(part.getBuyerName());
        if (blank(target.getBuyerVoen())) target.setBuyerVoen(part.getBuyerVoen());
        if (blank(target.getSellerName())) target.setSellerName(part.getSellerName());
        if (blank(target.getSellerVoen())) target.setSellerVoen(part.getSellerVoen());
        target.setEInvoiceNumber(mergeUniqueText(target.getEInvoiceNumber(), part.getEInvoiceNumber(), " / "));
        target.setNote(mergeUniqueText(target.getNote(), part.getNote(), " | "));
        target.getItems().addAll(part.getItems());
        target.setVat(target.getVat().add(part.getVat()));
    }

    private InvoiceData parseTable(Sheet s, TableHeader h, String mt, DataFormatter f, FormulaEvaluator ev) {
        InvoiceData d = new InvoiceData();
        d.setMtNumber(mt);
        d.setSourceSheet(s.getSheetName());
        LinkedHashSet<String> notes = new LinkedHashSet<>();
        LinkedHashSet<String> invoiceNos = new LinkedHashSet<>();

        for (int r = h.row + 1; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            String code = normalizeSerial(get(row, h, "serial", f, ev));
            if (!code.equals(mt)) continue;

            if (d.getInvoiceDate() == null) d.setInvoiceDate(getDate(row, h, "date", f, ev));
            if (blank(d.getBuyerName())) d.setBuyerName(get(row, h, "buyer", f, ev));
            if (blank(d.getBuyerVoen())) d.setBuyerVoen(get(row, h, "buyerVoen", f, ev));
            if (blank(d.getSellerName())) d.setSellerName(get(row, h, "seller", f, ev));
            if (blank(d.getSellerVoen())) d.setSellerVoen(get(row, h, "sellerVoen", f, ev));

            String eNo = get(row, h, "eInvoiceNo", f, ev).trim();
            if (!eNo.isBlank()) invoiceNos.add(eNo);
            String note = ExcelUtil.cleanNotePrefix(get(row, h, "note", f, ev));
            if (!note.isBlank()) notes.add(note);

            String name = get(row, h, "name", f, ev);
            BigDecimal qty = dec(row, h, "qty", f, ev);
            BigDecimal price = dec(row, h, "price", f, ev);
            BigDecimal amount = dec(row, h, "amount", f, ev);
            if (amount.signum() == 0 && qty.signum() != 0 && price.signum() != 0) amount = qty.multiply(price);
            if (!name.isBlank() || amount.signum() != 0) {
                d.getItems().add(new InvoiceItem(
                        name.isBlank() ? "Ehtiyat hissələri və xidmət" : name,
                        blankTo(get(row, h, "unit", f, ev), "ədəd"),
                        qty.signum() == 0 ? BigDecimal.ONE : qty,
                        price,
                        amount
                ));
            }
            BigDecimal vat = vatForRow(row, h, amount, f, ev);
            if (vat.signum() > 0) d.setVat(d.getVat().add(vat));
        }

        d.setEInvoiceNumber(String.join(" / ", invoiceNos));
        d.setNote(String.join(" | ", notes));
        d.setParserNote("MT sütununda dəqiq uyğun gələn bütün sətirlər oxundu.");
        return d;
    }

    private InvoiceData parseBlock(Sheet s, CellPos pos, String mt, DataFormatter f, FormulaEvaluator ev) {
        InvoiceData d = new InvoiceData();
        d.setMtNumber(mt);
        d.setSourceSheet(s.getSheetName());
        int from = Math.max(0, pos.row - 25), to = Math.min(s.getLastRowNum(), pos.row + 100);

        for (int r = from; r <= Math.min(to, pos.row + 30); r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            for (int c = 0; c <= Math.min(Math.max(row.getLastCellNum(), 0), 30); c++) {
                String raw = ExcelUtil.text(row.getCell(c), f, ev);
                String t = ExcelUtil.norm(raw);
                if (t.contains("tarix") && d.getInvoiceDate() == null) {
                    LocalDate dt = ExcelUtil.date(row.getCell(c + 1), f, ev);
                    if (dt != null) d.setInvoiceDate(dt);
                }
                boolean taxLabel = t.contains("voen") || t.contains("tin") || t.contains("vergi");
                if (!taxLabel && (t.contains("alici") || t.contains("mal alan") || t.contains("malalan") || t.contains("musteri") || t.contains("sifarisci") || t.contains("qebul eden")) && blank(d.getBuyerName()))
                    d.setBuyerName(ExcelUtil.text(row.getCell(c + 1), f, ev));
                if (!taxLabel && (t.contains("satici") || t.contains("mal gonderen") || t.contains("malgonderen") || t.contains("tehcizatci") || t.contains("icraci") || t.contains("gonderen")) && blank(d.getSellerName()))
                    d.setSellerName(ExcelUtil.text(row.getCell(c + 1), f, ev));
                if (isEInvoiceLabel(t) && blank(d.getEInvoiceNumber()))
                    d.setEInvoiceNumber(ExcelUtil.text(row.getCell(c + 1), f, ev));
                if (isNoteLabel(t) && blank(d.getNote()))
                    d.setNote(ExcelUtil.cleanNotePrefix(ExcelUtil.text(row.getCell(c + 1), f, ev)));
            }
        }

        for (int r = Math.max(0, pos.row - 5); r <= to; r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            TableHeader h = headerFromRow(row, r, f, ev);
            if (h != null && h.cols.containsKey("name") && (h.cols.containsKey("amount") || h.cols.containsKey("price"))) {
                int empty = 0;
                for (int rr = r + 1; rr <= Math.min(s.getLastRowNum(), r + 100); rr++) {
                    Row dr = s.getRow(rr);
                    if (dr == null) { if (++empty >= 3) break; continue; }
                    String name = get(dr, h, "name", f, ev);
                    BigDecimal qty = dec(dr, h, "qty", f, ev), price = dec(dr, h, "price", f, ev), amount = dec(dr, h, "amount", f, ev);
                    if (name.isBlank() && qty.signum() == 0 && price.signum() == 0 && amount.signum() == 0) { if (++empty >= 3) break; continue; }
                    empty = 0;
                    String nn = ExcelUtil.norm(name);
                    if (nn.equals("cemi") || nn.contains("yekun") || nn.contains("toplam") || nn.contains("edv")) continue;
                    if (amount.signum() == 0 && qty.signum() != 0 && price.signum() != 0) amount = qty.multiply(price);
                    d.getItems().add(new InvoiceItem(
                            name.isBlank() ? "Ehtiyat hissələri və xidmət" : name,
                            blankTo(get(dr, h, "unit", f, ev), "ədəd"),
                            qty.signum() == 0 ? BigDecimal.ONE : qty,
                            price,
                            amount
                    ));
                    BigDecimal vat = vatForRow(dr, h, amount, f, ev);
                    if (vat.signum() > 0) d.setVat(d.getVat().add(vat));
                    String note = ExcelUtil.cleanNotePrefix(get(dr, h, "note", f, ev));
                    if (!note.isBlank()) d.setNote(mergeUniqueText(d.getNote(), note, " | "));
                    String eNo = get(dr, h, "eInvoiceNo", f, ev);
                    if (!eNo.isBlank()) d.setEInvoiceNumber(mergeUniqueText(d.getEInvoiceNumber(), eNo, " / "));
                }
                break;
            }
        }
        d.setParserNote("MT blokuna aid mal cədvəli üzrə oxundu.");
        return d;
    }

    private InvoiceData finalizeTotals(InvoiceData d, String requestedMode) {
        String mode = VAT_AUTO; // V5.3: ƏDV yalnız Excel məlumatından avtomatik müəyyən edilir.
        BigDecimal rawSubtotal = d.getItems().stream().map(InvoiceItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal parsedVat = normalizeParsedVat(d.getVat(), rawSubtotal, d.getItems().size());

        if (VAT_INCLUDED.equals(mode)) {
            BigDecimal gross = rawSubtotal.setScale(2, RoundingMode.HALF_UP);
            List<InvoiceItem> netItems = new ArrayList<>();
            for (InvoiceItem item : d.getItems()) {
                BigDecimal netAmount = item.amount().divide(VAT_DIVISOR, 2, RoundingMode.HALF_UP);
                BigDecimal netPrice = item.unitPrice().signum() == 0 ? BigDecimal.ZERO : item.unitPrice().divide(VAT_DIVISOR, 4, RoundingMode.HALF_UP);
                netItems.add(new InvoiceItem(item.name(), item.unit(), item.quantity(), netPrice, netAmount));
            }
            d.getItems().clear();
            d.getItems().addAll(netItems);
            BigDecimal net = d.getItems().stream().map(InvoiceItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            d.setSubtotal(net);
            d.setVat(gross.subtract(net).setScale(2, RoundingMode.HALF_UP));
            d.setTotal(gross);
            d.setVatLabel("+18% (ƏDV, məbləğə daxildir)");
        } else if (VAT_EXCLUSIVE.equals(mode)) {
            BigDecimal net = rawSubtotal.setScale(2, RoundingMode.HALF_UP);
            BigDecimal vat = net.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP);
            d.setSubtotal(net);
            d.setVat(vat);
            d.setTotal(net.add(vat).setScale(2, RoundingMode.HALF_UP));
            d.setVatLabel("+18% (ƏDV)");
        } else if (VAT_NONE.equals(mode)) {
            BigDecimal net = rawSubtotal.setScale(2, RoundingMode.HALF_UP);
            d.setSubtotal(net);
            d.setVat(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            d.setTotal(net);
            d.setVatLabel("ƏDV-siz");
        } else {
            BigDecimal net = rawSubtotal.setScale(2, RoundingMode.HALF_UP);
            BigDecimal vat = parsedVat.setScale(2, RoundingMode.HALF_UP);
            d.setSubtotal(net);
            d.setVat(vat);
            d.setTotal(net.add(vat).setScale(2, RoundingMode.HALF_UP));
            d.setVatLabel(vat.signum() > 0 ? "+18% (ƏDV)" : "ƏDV-siz");
            mode = VAT_AUTO;
        }

        d.setVatMode(mode);
        if (d.getInvoiceDate() == null) d.setInvoiceDate(LocalDate.now());
        if (blank(d.getEInvoiceNumber())) d.setEInvoiceNumber(d.getMtNumber());
        return d;
    }

    private BigDecimal normalizeParsedVat(BigDecimal rawVat, BigDecimal subtotal, int itemCount) {
        if (rawVat == null || rawVat.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal expected = subtotal.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP);
        if (itemCount > 1 && rawVat.compareTo(expected.multiply(new BigDecimal("1.5"))) > 0) {
            BigDecimal avg = rawVat.divide(BigDecimal.valueOf(itemCount), 2, RoundingMode.HALF_UP);
            if (avg.subtract(expected).abs().compareTo(new BigDecimal("0.10")) <= 0) return avg;
        }
        return rawVat;
    }

    private String normalizeVatMode(String mode) {
        if (mode == null) return VAT_AUTO;
        return switch (mode.trim().toUpperCase(Locale.ROOT)) {
            case VAT_EXCLUSIVE, "EXCLUSIVE", "EDV_XARIC", "VAT_EXCLUSIVE" -> VAT_EXCLUSIVE;
            case VAT_INCLUDED, "INCLUDED", "EDV_DAXIL", "VAT_INCLUDED" -> VAT_INCLUDED;
            case VAT_NONE, "NONE", "EDVSIZ", "EDV_SIZ" -> VAT_NONE;
            default -> VAT_AUTO;
        };
    }

    private record CellPos(int row, int col) {}

    private CellPos findExactOrContaining(Sheet s, String mt, DataFormatter f, FormulaEvaluator ev) {
        for (Row row : s) for (Cell c : row) {
            String raw = ExcelUtil.text(c, f, ev);
            if (containsExactSerial(raw, mt)) return new CellPos(row.getRowNum(), c.getColumnIndex());
        }
        return null;
    }

    private String normalizeSerial(String value) {
        if (value == null) return "";
        String extracted = extractSerialOnly(value);
        return (extracted.isBlank() ? value : extracted).trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String extractSerialOnly(String value) {
        if (value == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\bMT[A-Z0-9_-]{6,}\\b").matcher(value);
        return m.find() ? m.group() : "";
    }

    private boolean containsExactSerial(String text, String serial) {
        if (text == null || text.isBlank()) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\bMT[A-Z0-9_-]{6,}\\b").matcher(text);
        while (m.find()) if (normalizeSerial(m.group()).equals(serial)) return true;
        return normalizeSerial(text).equals(serial);
    }

    private record TableHeader(int row, Map<String,Integer> cols) {}

    private TableHeader detectTableHeader(Sheet s, DataFormatter f, FormulaEvaluator ev) {
        for (int r = s.getFirstRowNum(); r <= Math.min(s.getLastRowNum(), 1000); r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            TableHeader h = headerFromRow(row, r, f, ev);
            if (h != null && !h.cols.containsKey("serial") && h.cols.containsKey("eInvoiceNo")) {
                int candidate = h.cols.get("eInvoiceNo");
                boolean containsMt = false;
                for (int rr = r + 1; rr <= Math.min(s.getLastRowNum(), r + 12); rr++) {
                    Row sample = s.getRow(rr);
                    if (sample != null && !extractSerial(ExcelUtil.text(sample.getCell(candidate), f, ev)).isBlank()) { containsMt = true; break; }
                }
                if (containsMt) {
                    Map<String,Integer> remapped = new HashMap<>(h.cols);
                    remapped.put("serial", candidate);
                    remapped.remove("eInvoiceNo");
                    h = new TableHeader(r, remapped);
                }
            }
            if (h != null && h.cols.size() >= 2 && (h.cols.containsKey("serial") || h.cols.containsKey("name"))) return h;
        }
        return null;
    }

    private TableHeader headerFromRow(Row row, int r, DataFormatter f, FormulaEvaluator ev) {
        Map<String,Integer> m = new HashMap<>();
        for (Cell c : row) {
            String k = headerKey(ExcelUtil.norm(ExcelUtil.text(c, f, ev)));
            if (k != null) m.putIfAbsent(k, c.getColumnIndex());
        }
        return m.size() >= 2 ? new TableHeader(r, m) : null;
    }

    private String headerKey(String n) {
        if (n == null || n.isBlank()) return null;

        // MT / seriya-nömrə başlıqları müxtəlif ixrac formatlarında fərqli yazıla bilər.
        if (n.equals("mt") || n.equals("mt no") || n.equals("mt nomresi") ||
                (n.contains("mt") && (n.contains("qaime") || n.contains("seriya") || n.contains("nomre") || n.contains("kod"))) ||
                n.equals("seriya") || n.equals("seriya nomresi") ||
                (n.contains("seriya") && n.contains("nomre")) ||
                (n.contains("qaime") && n.contains("seriya")) ||
                (n.contains("elektron qaime") && n.contains("seriya"))) return "serial";

        if (isEInvoiceLabel(n)) return "eInvoiceNo";
        if (isNoteLabel(n)) return "note";
        if (n.equals("nomre") || n.equals("nomresi") || n.equals("number")) return "numberPart";

        // Alıcı/Satıcı başlıqları mal adı başlığından ƏVVƏL yoxlanılır.
        // Məs.: "Mal göndərənin adı" əvvəlki versiyada içində "mal"+"adı" olduğu üçün
        // səhvən məhsul adı kimi tanına bilirdi.
        boolean buyerHeader = n.contains("alici") || n.contains("mal alan") || n.contains("malalan") ||
                n.contains("musteri") || n.contains("sifarisci") || n.contains("buyer") ||
                n.contains("alan terefin") || n.contains("alan teref") || n.contains("qebul eden");
        if (buyerHeader) {
            if (n.contains("voen") || n.contains("tin") || n.contains("vergi")) return "buyerVoen";
            return "buyer";
        }
        boolean sellerHeader = n.contains("satici") || n.contains("mal gonderen") || n.contains("malgonderen") ||
                n.contains("tehcizatci") || n.contains("icraci") || n.contains("seller") ||
                n.contains("gonderen terefin") || n.contains("gonderen teref") || n.equals("gonderen") || n.startsWith("gonderen ");
        if (sellerHeader) {
            if (n.contains("voen") || n.contains("tin") || n.contains("vergi")) return "sellerVoen";
            return "seller";
        }

        // Məs.: "Malın (işin, xidmətin) adı", "Malların / xidmətlərin təsviri" və s.
        if (n.equals("adi") || n.contains("nomenklatura") || n.contains("tesvir") || n.contains("description") ||
                ((n.contains("mal") || n.contains("mehsul") || n.contains("xidmet") || n.contains("is")) && n.contains("adi"))) return "name";
        if (n.contains("olcu vahidi") || n.contains("vahid adi") || n.equals("vahid") || n.equals("unit")) return "unit";
        if (n.contains("miqdar") || n.contains("hecm") || n.equals("say") || n.equals("quantity")) return "qty";
        if ((n.contains("vahid") && n.contains("qiymet")) || n.contains("bir vahidin qiymeti") || n.equals("qiymet") || n.equals("price")) return "price";
        if ((n.contains("edv") || n.contains("vat")) && (n.contains("derece") || n.contains("faiz") || n.contains("rate"))) return "vatRate";
        if (n.equals("edv") || n.contains("edv mebleg") || n.contains("hesablanmis edv") || n.contains("vat amount")) return "vat";
        if (n.contains("mebleg") || (n.contains("cem") && n.contains("qiymet")) || n.equals("amount") || n.equals("tutar")) return "amount";
        if (n.contains("tarix") || n.equals("date")) return "date";
        if (n.contains("status")) return "status";
        if (n.equals("novu") || n.equals("nov") || n.contains("qaime novu") || n.contains("qaimenin novu")) return "type";
        return null;
    }

    private boolean isEInvoiceLabel(String n) {
        if (n == null || n.isBlank()) return false;
        return n.equals("e qaime") || n.equals("e qaime no") || n.equals("e qaime nomresi") ||
                n.contains("e qaimenin nomresi") || n.contains("elektron qaime nomresi") ||
                n.contains("elektron qaime no") ||
                ((n.equals("qaime no") || n.equals("qaime nomresi") || n.contains("qaimenin nomresi")) && !n.contains("mt") && !n.contains("seriya"));
    }

    private boolean isNoteLabel(String n) {
        if (n == null || n.isBlank()) return false;
        return n.equals("qeyd") || n.equals("qeydler") || n.equals("note") || n.contains("qaime qeydi") ||
                n.contains("qaimenin qeyd") || n.contains("elave qeyd") || n.equals("aciqlama") || n.equals("izah");
    }

    private BigDecimal vatForRow(Row row, TableHeader h, BigDecimal amount, DataFormatter f, FormulaEvaluator ev) {
        BigDecimal explicit = dec(row, h, "vat", f, ev);
        if (explicit.signum() > 0) return explicit;
        BigDecimal rate = dec(row, h, "vatRate", f, ev);
        if (rate.signum() <= 0 || amount == null || amount.signum() == 0) return BigDecimal.ZERO;
        if (rate.compareTo(BigDecimal.ONE) > 0) rate = rate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private String get(Row row, TableHeader h, String key, DataFormatter f, FormulaEvaluator ev) {
        Integer c = h.cols.get(key);
        return c == null ? "" : ExcelUtil.text(row.getCell(c), f, ev);
    }

    private BigDecimal dec(Row row, TableHeader h, String key, DataFormatter f, FormulaEvaluator ev) {
        return ExcelUtil.decimal(get(row, h, key, f, ev));
    }

    private LocalDate getDate(Row row, TableHeader h, String key, DataFormatter f, FormulaEvaluator ev) {
        Integer c = h.cols.get(key);
        return c == null ? null : ExcelUtil.date(row.getCell(c), f, ev);
    }

    private String blankTo(String s, String x) { return blank(s) ? x : s; }
    private boolean blank(String s) { return s == null || s.isBlank(); }

    private String mergeUniqueText(String a, String b, String separator) {
        if (blank(a)) return blank(b) ? "" : b.trim();
        if (blank(b)) return a.trim();
        String aa = ExcelUtil.cleanNotePrefix(a), bb = ExcelUtil.cleanNotePrefix(b);
        for (String part : aa.split(java.util.regex.Pattern.quote(separator))) if (part.trim().equalsIgnoreCase(bb)) return aa;
        return aa + separator + bb;
    }
}
