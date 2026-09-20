package az.gmb.taxdata.service;

import az.gmb.taxdata.model.*;
import az.gmb.taxdata.auth.CurrentUserContext;
import az.gmb.taxdata.util.AzeriNumberWords;
import az.gmb.taxdata.util.ExcelUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.io.UncheckedIOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class DocumentGeneratorService {
    private final StorageService storage;
    private final InvoiceExcelService invoices;
    private final WorkspaceDataService workspaceData;
    private final CompanyRegistryService companyRegistry;
    private final ObjectMapper mapper;
    private final GeneratedDocumentArchiveService generatedArchive;
    private final Map<String,byte[]> templateBytesCache = new ConcurrentHashMap<>();

    public DocumentGeneratorService(StorageService storage, InvoiceExcelService invoices,
                                    WorkspaceDataService workspaceData, CompanyRegistryService companyRegistry, ObjectMapper mapper, GeneratedDocumentArchiveService generatedArchive) {
        this.storage = storage;
        this.invoices = invoices;
        this.workspaceData = workspaceData;
        this.companyRegistry = companyRegistry;
        this.mapper = mapper;
        this.generatedArchive = generatedArchive;
    }

    public BatchGenerationResult generate(GenerationRequest req) throws IOException {
        String ws = storage.normalizeWorkspaceId(req.workspaceId());
        List<CompanyInfo> workspaceCompanies = companyRegistry.list(CurrentUserContext.require().id(), ws);
        WorkspaceState generationState = workspaceData.getState(ws);
        // SQL registry is the sole authority for party requisites. Client-supplied
        // company objects and legacy company-upload IDs are intentionally ignored.
        CompanyInfo defaultExecutor = !blank(req.executorCompanyId()) ? findCompany(workspaceCompanies, req.executorCompanyId(), "İcraçı") : null;
        CompanyInfo defaultOrderer = !blank(req.ordererCompanyId()) ? findCompany(workspaceCompanies, req.ordererCompanyId(), "Sifarişçi") : null;

        LinkedHashSet<String> selectedInvoiceNumbers = new LinkedHashSet<>();
        if (req.invoiceNumbers() != null) for (String no : req.invoiceNumbers()) if (!blank(no)) selectedInvoiceNumbers.add(no.trim());
        if (selectedInvoiceNumbers.isEmpty()) throw new IllegalArgumentException("Ən azı bir qaimə seçilməlidir.");

        List<InvoiceData> selectedInvoices = new ArrayList<>();
        List<StoredInvoice> storedSelections = new ArrayList<>();
        for (String invoiceNumber : selectedInvoiceNumbers) {
            try { storedSelections.add(workspaceData.getStoredInvoice(ws, invoiceNumber)); }
            catch (IllegalArgumentException ex) {
                if (!blank(req.invoiceUploadId())) selectedInvoices.add(invoices.parseUploadByInvoiceNumber(ws, req.invoiceUploadId(), invoiceNumber, "AUTO"));
                else throw ex;
            }
        }

        // Sürətli batch oxunuşu: eyni böyük Excel hər qaimə üçün yenidən açılmır.
        Map<String,List<StoredInvoice>> byUpload = new LinkedHashMap<>();
        for (StoredInvoice stored : storedSelections) {
            if (stored.isManual() && stored.getManualData() != null) { selectedInvoices.add(copyInvoice(stored.getManualData())); continue; }
            if (blank(stored.getSourceUploadId())) throw new IllegalArgumentException("Qaimənin mənbə Excel-i tapılmadı: " + stored.getInvoiceNumber());
            byUpload.computeIfAbsent(stored.getSourceUploadId(), k -> new ArrayList<>()).add(stored);
        }
        for (var entry : byUpload.entrySet()) {
            List<String> mts = entry.getValue().stream().map(StoredInvoice::getMtNumber).filter(x -> !blank(x)).toList();
            Map<String,InvoiceData> parsedBatch = invoices.parseManyUpload(ws, entry.getKey(), mts);
            for (StoredInvoice stored : entry.getValue()) {
                InvoiceData parsed;
                if (!blank(stored.getMtNumber())) parsed = parsedBatch.get(stored.getMtNumber().trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""));
                else parsed = null;
                if (parsed == null) parsed = invoices.parseUploadByInvoiceNumber(ws, stored.getSourceUploadId(), stored.getInvoiceNumber(), "AUTO");
                selectedInvoices.add(enrichFromStored(parsed, stored));
            }
        }

        // Sənəd nömrəsi qaimənin öz tarixinə görə köhnədən yeniyə verilir.
        selectedInvoices.sort(Comparator.comparing(InvoiceData::getInvoiceDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(x -> safe(x.getEInvoiceNumber()), String.CASE_INSENSITIVE_ORDER));

        String startRaw = firstNonBlank(req.startingDocumentNo(), firstNonBlank(req.invoiceNo(), "1"));
        NumberSequence sequence = numberSequence(startRaw);

        String firstSelection = selectedInvoices.isEmpty() ? "qaimeler" : firstNonBlank(selectedInvoices.get(0).getEInvoiceNumber(), selectedInvoices.get(0).getMtNumber());
        String batchBase = selectedInvoices.size() == 1 ? sanitize(firstSelection) + "_alt_senedler" : "coxlu_qaimeler_alt_senedler";
        Path out = storage.createOutputFolder(ws, batchBase);
        List<GeneratedInvoiceResult> results = new ArrayList<>();
        List<InvoiceData> manifestInvoices = new ArrayList<>();
        LinkedHashMap<String,String> documentNumbers = new LinkedHashMap<>();
        LinkedHashMap<String,CompanyInfo> manifestExecutors = new LinkedHashMap<>();
        LinkedHashMap<String,CompanyInfo> manifestOrderers = new LinkedHashMap<>();
        LinkedHashMap<String,String> manifestContractNos = new LinkedHashMap<>();
        LinkedHashMap<String,String> manifestContractDates = new LinkedHashMap<>();
        long distinctBuyers = selectedInvoices.stream().map(x -> normalizePartyName(x.getBuyerName())).filter(x -> !x.isBlank()).distinct().count();
        boolean mixedBuyers = distinctBuyers > 1;

        for (int i = 0; i < selectedInvoices.size(); i++) {
            InvoiceData invoice = selectedInvoices.get(i);
            String docNo = sequence.value(i);
            String key = firstNonBlank(invoice.getEInvoiceNumber(), invoice.getMtNumber());
            documentNumbers.put(key, docNo);

            CompanyInfo executor = resolveInvoiceParty(workspaceCompanies, invoice.getSellerName(), invoice.getSellerVoen(), defaultExecutor, "Satıcı", key);
            CompanyInfo orderer = resolveInvoiceParty(workspaceCompanies, invoice.getBuyerName(), invoice.getBuyerVoen(), defaultOrderer, "Alıcı", key);
            String entity = orderer.getEntityType();
            String hfTemplate = templateForInvoice(generationState, "hf", entity, defaultOrderer, req.hfTemplateUploadId());
            String qrTemplate = templateForInvoice(generationState, "qr", entity, defaultOrderer, req.qrTemplateUploadId());
            String ttTemplate = templateForInvoice(generationState, "tt", entity, defaultOrderer, req.ttTemplateUploadId());
            Map<String,String> hfMapping = mappingFor(generationState,"hf",entity);
            Map<String,String> qrMapping = mappingFor(generationState,"qr",entity);
            Map<String,String> ttMapping = mappingFor(generationState,"tt",entity);
            String requestContractNo = mixedBuyers ? "" : req.contractNo();
            String requestContractDate = mixedBuyers ? "" : req.contractDate();
            String contractNo = firstNonBlank(requestContractNo, firstNonBlank(orderer.getContractNo(), extractContractNo(invoice.getNote())));
            String contractDate = firstNonBlank(requestContractDate, firstNonBlank(orderer.getContractDate(), extractContractDate(invoice.getNote())));
            manifestExecutors.put(key, executor); manifestOrderers.put(key, orderer);
            manifestContractNos.put(key, contractNo); manifestContractDates.put(key, contractDate);

            LocalDate docDate = parseDate(req.documentDate(), invoice.getInvoiceDate());
            invoice.setInvoiceDate(docDate);
            if (!blank(req.noteOverride())) invoice.setNote(ExcelUtil.cleanNotePrefix(req.noteOverride()));
            else invoice.setNote(ExcelUtil.cleanNotePrefix(invoice.getNote()));
            manifestInvoices.add(invoice);

            String mtSafe = sanitize(firstNonBlank(invoice.getMtNumber(), key));
            String hf = "01_Hesab_Faktura_" + docNo + "_" + mtSafe + ".xlsx";
            String qr = "02_Qiymet_Razilasma_" + docNo + "_" + mtSafe + ".xlsx";
            String tt = "03_Tehvil_Teslim_" + docNo + "_" + mtSafe + ".xlsx";

            // V6.1: HF/QR/TT müstəqil workbook-lardır; Java 21 virtual thread-lərlə paralel yaradılır.
            // Böyük batch-də Excel oxunuşu artıq bir dəfə edilir, bu hissə isə üç sənədin yazılmasını paralelləşdirir.
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> hfFuture = pool.submit(() -> { try { generateHf(out.resolve(hf), ws, executor, orderer, invoice, docDate, contractNo, contractDate, docNo, hfTemplate, hfMapping); } catch (IOException e) { throw new UncheckedIOException(e); } });
                Future<?> qrFuture = pool.submit(() -> { try { generateQr(out.resolve(qr), ws, executor, orderer, invoice, docDate, contractNo, contractDate, docNo, qrTemplate, qrMapping); } catch (IOException e) { throw new UncheckedIOException(e); } });
                Future<?> ttFuture = pool.submit(() -> { try { generateTt(out.resolve(tt), ws, executor, orderer, invoice, docDate, contractNo, contractDate, docNo, ttTemplate, ttMapping); } catch (IOException e) { throw new UncheckedIOException(e); } });
                waitGeneration(hfFuture); waitGeneration(qrFuture); waitGeneration(ttFuture);
            }

            String base = "/api/output/" + out.getFileName() + "/"; String q = "";
            results.add(new GeneratedInvoiceResult(invoice.getMtNumber(), invoice.getEInvoiceNumber(), docNo, List.of(
                    new DocumentFileInfo("hf", hf, base + hf + q), new DocumentFileInfo("qr", qr, base + qr + q), new DocumentFileInfo("tt", tt, base + tt + q)
            ), invoice));
        }

        CompanyInfo manifestExecutor = defaultExecutor != null ? defaultExecutor : manifestExecutors.values().stream().findFirst().orElse(null);
        CompanyInfo manifestOrderer = defaultOrderer != null ? defaultOrderer : manifestOrderers.values().stream().findFirst().orElse(null);
        String manifestContractNo = firstNonBlank(req.contractNo(), manifestOrderer == null ? "" : manifestOrderer.getContractNo());
        String manifestContractDate = firstNonBlank(req.contractDate(), manifestOrderer == null ? "" : manifestOrderer.getContractDate());
        BatchManifest manifest = new BatchManifest(out.getFileName().toString(), manifestExecutor, manifestOrderer,
                manifestContractNo, manifestContractDate, startRaw, documentNumbers, manifestExecutors, manifestOrderers,
                manifestContractNos, manifestContractDates, manifestInvoices);
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("manifest.json").toFile(), manifest);
        String zipName = "alt_senedler.zip"; zipFolder(out, out.resolve(zipName));
        String folder = out.getFileName().toString();
        String download = "/api/output/" + folder + "/" + zipName;
        String preview = "/api/print/" + folder + "?hfCopies=1&ttCopies=1&qrCopies=1&preview=1";
        String print = "/api/print/" + folder;
        GenerationArchiveRecord archived = generatedArchive.register(ws, out, manifest, req);
        return new BatchGenerationResult(folder, download, preview, print, results, archived.getId());
    }


    private InvoiceData enrichFromStored(InvoiceData d, StoredInvoice s) {
        if (d == null) d = new InvoiceData();
        String parsedNo=safe(d.getEInvoiceNumber());
        String parsedMt=safe(d.getMtNumber());
        if (!blank(s.getInvoiceNumber()) && (blank(parsedNo) || (!blank(parsedMt) && parsedNo.equalsIgnoreCase(parsedMt)))) d.setEInvoiceNumber(s.getInvoiceNumber());
        if (!blank(s.getMtNumber())) d.setMtNumber(s.getMtNumber());
        if (s.getInvoiceDate() != null) d.setInvoiceDate(s.getInvoiceDate());
        if (!blank(s.getBuyerName())) d.setBuyerName(s.getBuyerName());
        if (!blank(s.getBuyerVoen())) d.setBuyerVoen(s.getBuyerVoen());
        if (!blank(s.getSellerName())) d.setSellerName(s.getSellerName());
        if (!blank(s.getSellerVoen())) d.setSellerVoen(s.getSellerVoen());
        if (!blank(s.getNote())) d.setNote(ExcelUtil.cleanNotePrefix(s.getNote()));
        else d.setNote(ExcelUtil.cleanNotePrefix(d.getNote()));
        if (d.getVat() == null || (d.getVat().signum() == 0 && s.getVat() != null && s.getVat().signum() > 0)) {
            d.setVat(s.getVat());
            if (d.getSubtotal() != null) d.setTotal(d.getSubtotal().add(s.getVat()));
            d.setVatLabel(s.getVatLabel());
        }
        return d;
    }

    private CompanyInfo resolveInvoiceParty(List<CompanyInfo> companies,String excelName,String excelVoen,CompanyInfo fallback,String label,String invoiceKey){
        String tax=safe(excelVoen).replaceAll("\\D","");
        if(!tax.isBlank()){
            CompanyInfo byVoen=companies.stream().filter(c -> safe(c.getVoen()).replaceAll("\\D","").equals(tax)).findFirst().orElse(null);
            if(byVoen!=null)return byVoen;
        }
        String wanted=normalizePartyName(excelName);
        if(wanted.isBlank()){
            if(fallback!=null)return fallback;
            throw new IllegalArgumentException(invoiceKey+" qaiməsində "+label+" adı/VÖEN boşdur və fallback tərəf seçilməyib.");
        }
        CompanyInfo exact=companies.stream().filter(c -> normalizePartyName(c.getCompany()).equals(wanted)).findFirst().orElse(null);
        if(exact!=null)return exact;
        CompanyInfo partial=companies.stream().filter(c -> {String n=normalizePartyName(c.getCompany());return !n.isBlank()&&(n.contains(wanted)||wanted.contains(n));}).findFirst().orElse(null);
        if(partial!=null)return partial;
        if(fallback!=null){String f=normalizePartyName(fallback.getCompany());if(f.equals(wanted)||(!f.isBlank()&&(f.contains(wanted)||wanted.contains(f))))return fallback;}
        throw new IllegalArgumentException(invoiceKey+" qaiməsi üçün "+label+" «"+safe(excelName)+"» / VÖEN "+tax+" SQL rekvizit bazasında tapılmadı. Əvvəl həmin tərəfi Şirkət bazasına əlavə edin.");
    }

    private String normalizePartyName(String s){
        if(s==null)return "";
        String x=s.toLowerCase(Locale.ROOT).replace('ə','e').replace('ı','i').replace('ö','o').replace('ü','u').replace('ş','s').replace('ç','c').replace('ğ','g');
        return java.text.Normalizer.normalize(x,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","").replaceAll("[^a-z0-9]+","");
    }

    private String templateForInvoice(WorkspaceState state,String type,String entity,CompanyInfo defaultOrderer,String requestUploadId) {
        // Production uses one bundled factual template source for every computer.
        // Per-workspace uploads are intentionally ignored to eliminate layout drift.
        return "";
    }
    private Map<String,String> mappingFor(WorkspaceState state,String type,String entity){
        // Standard template cell positions are authoritative on every device.
        return Map.of();
    }

    private String extractContractNo(String note){
        String x=safe(note);
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?iu)\\b([A-ZƏÖÜĞİŞÇ]{2,12}\\s*/\\s*\\d{1,8}\\s*/\\s*\\d{4})\\b").matcher(x);
        if(m.find())return m.group(1).replaceAll("\\s+","");
        m=java.util.regex.Pattern.compile("(?iu)(?:müqavil[əe]|muqavil[əe])\\s*(?:№|no|nömr[əe]li)?\\s*[:#-]?\\s*([A-Z0-9][A-Z0-9/.-]{3,30})").matcher(x);
        return m.find()?m.group(1).trim():"";
    }

    private String extractContractDate(String note){
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?<!\\d)([0-3]?\\d[./-][01]?\\d[./-](?:19|20)\\d{2})(?!\\d)").matcher(safe(note));
        return m.find()?m.group(1).replace('/','.') : "";
    }

    private InvoiceData copyInvoice(InvoiceData src) {
        InvoiceData d = new InvoiceData(); d.setMtNumber(src.getMtNumber()); d.setEInvoiceNumber(src.getEInvoiceNumber()); d.setNote(src.getNote());
        d.setInvoiceDate(src.getInvoiceDate()); d.setBuyerName(src.getBuyerName()); d.setBuyerVoen(src.getBuyerVoen()); d.setSellerName(src.getSellerName()); d.setSellerVoen(src.getSellerVoen()); d.getItems().addAll(src.getItems());
        d.setSubtotal(src.getSubtotal()); d.setVat(src.getVat()); d.setTotal(src.getTotal()); d.setVatMode("AUTO"); d.setVatLabel(src.getVatLabel()); d.setSourceSheet(src.getSourceSheet()); d.setParserNote(src.getParserNote()); return d;
    }

    private record NumberSequence(int start, int width) {
        String value(int offset) { String raw = Integer.toString(start + offset); return width > 1 ? String.format("%0" + width + "d", start + offset) : raw; }
    }
    private NumberSequence numberSequence(String raw) {
        String x = safe(raw).trim(); if (!x.matches("\\d+")) x = "1";
        try { return new NumberSequence(Math.max(0, Integer.parseInt(x)), x.length()); } catch (Exception e) { return new NumberSequence(1, 1); }
    }

    public byte[] getDefaultTemplate(String type) throws IOException { return getDefaultTemplate(type, CompanyInfo.LEGAL); }

    public byte[] getDefaultTemplate(String type, String entityType) throws IOException {
        try (InputStream in = new ClassPathResource("excel-templates/" + defaultName(type, entityType)).getInputStream()) {
            return prepareTemplateDownload(in.readAllBytes());
        }
    }

    public byte[] getActiveTemplate(String workspaceId, String type, String entityType) throws IOException {
        return getDefaultTemplate(type, entityType);
    }

    private byte[] prepareTemplateDownload(byte[] source) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(source)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet primary = templateSheet(wb);
            for(int i=0;i<wb.getNumberOfSheets();i++){
                if(wb.isSheetHidden(i)||wb.isSheetVeryHidden(i))continue;
                stabilizeTextAndRows(wb,wb.getSheetAt(i));
            }
            configureA4Print(wb, primary, -1);
            wb.write(out);
            return out.toByteArray();
        }
    }


    private void generateHf(Path target, String ws, CompanyInfo ex, CompanyInfo or, InvoiceData inv, LocalDate date,
                            String contractNo, String contractDate, String invoiceNo, String customTemplateUploadId, Map<String,String> map) throws IOException {
        try (XSSFWorkbook wb = openTemplate(ws, "hf", customTemplateUploadId, or.getEntityType())) {
            Sheet s = templateSheet(wb);
            mappedString(s,map,"seller.company","C3",safe(ex.getCompany()));
            mappedString(s,map,"seller.voen","E3",prefixed("VÖEN: ", ex.getVoen()));
            mappedString(s,map,"seller.bankName","C4",safe(ex.getBankName()));
            mappedString(s,map,"seller.bankVoen","E4",prefixed("VÖEN: ", ex.getBankVoen()));
            mappedString(s,map,"seller.bankSwift","C5",prefixed("S.W.İ.F.T: ", ex.getBankSwift()));
            mappedString(s,map,"seller.bankCode","E5",prefixed(" КОD: ", ex.getBankCode()));
            mappedString(s,map,"seller.correspondent","C6",prefixed("М/h: ", ex.getCorrespondentAccount()));
            mappedString(s,map,"seller.address","C7",prefixed("Şirkət ünvanı: ", ex.getAddress()));
            mappedString(s,map,"seller.bankAccount","C8",prefixed("H/h: ", ex.getBankAccount()));
            mappedString(s,map,"document.number","F4","№ " + safeNo(invoiceNo));
            mappedDate(s,map,"document.date","F7",date);

            mappedString(s,map,"buyer.company","C10",safe(or.getCompany()));
            mappedString(s,map,"buyer.voen","E10",prefixed("VÖEN: ", or.getVoen()));
            mappedString(s,map,"buyer.bankName","C11",safe(or.getBankName()));
            mappedString(s,map,"buyer.bankVoen","E11",prefixed("VÖEN: ", or.getBankVoen()));
            mappedString(s,map,"buyer.bankSwift","C12",prefixed("S.W.İ.F.T: ", or.getBankSwift()));
            mappedString(s,map,"buyer.bankCode","E12",prefixed(" КОD: ", or.getBankCode()));
            mappedString(s,map,"buyer.correspondent","C13",prefixed("М/h: ", or.getCorrespondentAccount()));
            mappedString(s,map,"buyer.address","C14",prefixed("Şirkət ünvanı: ", or.getAddress()));
            mappedString(s,map,"buyer.bankAccount","C15",prefixed("H/h: ", or.getBankAccount()));
            mappedString(s,map,"contract.number","C16",blank(contractNo) ? "Müqavilə:" : "Müqavilə: " + contractNumberText(contractNo));
            mappedString(s,map,"contract.date","E16",blank(contractDate) ? "Tarix:" : "Tarix: " + contractDate);
            ExcelUtil.setString(s,"B17","");
            mappedString(s,map,"einvoice.number","C18","E-Qaimə № " + firstNonBlank(inv.getEInvoiceNumber(), inv.getMtNumber()));

            clearValues(s,19,26,1,5);
            int hfItemRow=mappedStartRow(map,"items.start",21);
            ExcelUtil.setString(s,"B"+hfItemRow,"Ehtiyat hissələri və xidmət"); ExcelUtil.setString(s,"C"+hfItemRow,"ədəd");
            ExcelUtil.setNumber(s,"D"+hfItemRow,BigDecimal.ONE); ExcelUtil.setNumber(s,"E"+hfItemRow,inv.getSubtotal()); ExcelUtil.setNumber(s,"F"+hfItemRow,inv.getSubtotal());
            mappedNumber(s,map,"subtotal","F28",inv.getSubtotal()); mappedString(s,map,"vat.label","E30",inv.getVatLabel());
            mappedNumber(s,map,"vat.amount","F30",inv.getVat()); mappedNumber(s,map,"total","F32",inv.getTotal());
            ExcelUtil.setString(s,"C35",AzeriNumberWords.money(inv.getTotal()));
            mappedString(s,map,"executor.signer","B41",ex.signerLabel()+":   "+ex.signerName()+"  ________________");
            finalizeGeneratedWorkbook(wb,s,hfItemRow-1);
            save(wb,target);
        }
    }

    private void generateQr(Path target, String ws, CompanyInfo ex, CompanyInfo or, InvoiceData inv, LocalDate date,
                            String contractNo, String contractDate, String priceProtocolNo, String customTemplateUploadId, Map<String,String> map) throws IOException {
        try (XSSFWorkbook wb = openTemplate(ws,"qr",customTemplateUploadId, or.getEntityType())) {
            Sheet s=templateSheet(wb); int year=date.getYear();
            mappedString(s,map,"document.number","A1","Qiymətlərin Razılaşdırma Protokolu № "+year+".R"+safeNo(priceProtocolNo));
            mappedDate(s,map,"document.date","F3",date);
            mappedString(s,map,"contract.number","A5",contractReferenceSentence(contractNo,contractDate));
            mappedString(s,map,"note","A9",noteText(inv.getNote()));
            int capacity=5,start=mappedStartRow(map,"items.start",11)-1,total=start+capacity; int shift=expandRows(s,start,total,capacity,inv.getItems().size(),5);
            clearValues(s,start,start+Math.max(capacity,inv.getItems().size())-1,0,5); fillItemsWithIndex(s,start,inv.getItems());
            // Editor mapping-i baza şablon koordinatlarında saxlanılır. Cədvəl böyüyəndə
            // total/imza kimi aşağı sahələrin custom mapping-ləri də avtomatik sürüşdürülür.
            Map<String,String> shiftedMap=shiftMappingRows(map,total+1,shift);
            int tr=total+shift; mappedNumber(s,shiftedMap,"subtotal","F"+(tr+1),inv.getSubtotal());
            mappedString(s,shiftedMap,"vat.label","A"+(tr+2),inv.getVatLabel()); mappedNumber(s,shiftedMap,"vat.amount","F"+(tr+2),inv.getVat()); mappedNumber(s,shiftedMap,"total","F"+(tr+3),inv.getTotal());
            int sig=start+10+shift;
            // QR imza bloku tərəfin hüquqi/fiziki tipinə görə tam dinamik qurulur.
            // Fiziki şəxs istər Satıcı, istər Sifarişçi olsun, eyni adın “şirkət + direktor” kimi
            // təkrar yazılması və ayrıca Satıcı/Sifarişçi başlığı göstərilmir. Yalnız bir dəfə
            // “Fiziki şəxs: Ad Soyad Ata adı” yazılır və altında imza xətti qalır.
            writeQrPartySignature(s,shiftedMap,ex,true,sig);
            writeQrPartySignature(s,shiftedMap,or,false,sig);
            finalizeGeneratedWorkbook(wb,s,inv.getItems().isEmpty()?-1:start+inv.getItems().size()-1);
            save(wb,target);
        }
    }

    private void generateTt(Path target, String ws, CompanyInfo ex, CompanyInfo or, InvoiceData inv, LocalDate date,
                            String contractNo, String contractDate, String handoverNo, String customTemplateUploadId, Map<String,String> map) throws IOException {
        try(XSSFWorkbook wb=openTemplate(ws,"tt",customTemplateUploadId, or.getEntityType())){
            Sheet s=templateSheet(wb); int year=date.getYear();
            mappedString(s,map,"document.number","A1","Təhvil-Təslim Aktı № "+year+".A"+safeNo(handoverNo));
            mappedString(s,map,"seller.company","A4",safe(ex.getCompany())); mappedString(s,map,"buyer.company","C4",safe(or.getCompany()));
            mappedString(s,map,"seller.voen","A5",prefixed("VÖEN: ",ex.getVoen())); mappedString(s,map,"buyer.voen","C5",prefixed("VÖEN: ",or.getVoen()));
            ExcelUtil.setString(s,"A6",ex.signerLabel()+": "+ex.signerName()); ExcelUtil.setString(s,"C6",or.signerLabel()+": "+or.signerName());
            ExcelUtil.setString(s,"A9",ex.sealSignatureLabel()); ExcelUtil.setString(s,"C9",or.sealSignatureLabel()); mappedDate(s,map,"document.date","F12",date);
            mappedString(s,map,"contract.number","A14",handoverIntro(ex,or,contractNo,contractDate)); mappedString(s,map,"note","A16",noteText(inv.getNote()));
            int capacity=5,start=mappedStartRow(map,"items.start",18)-1,total=start+capacity; int shift=expandRows(s,start,total,capacity,inv.getItems().size(),5);
            clearValues(s,start,start+Math.max(capacity,inv.getItems().size())-1,0,5); fillItemsWithIndex(s,start,inv.getItems());
            Map<String,String> shiftedMap=shiftMappingRows(map,total+1,shift);
            int tr=total+shift; mappedNumber(s,shiftedMap,"subtotal","F"+(tr+1),inv.getSubtotal()); mappedString(s,shiftedMap,"vat.label","A"+(tr+2),inv.getVatLabel());
            mappedNumber(s,shiftedMap,"vat.amount","F"+(tr+2),inv.getVat()); mappedNumber(s,shiftedMap,"total","F"+(tr+3),inv.getTotal());
            int bottom=start+12+shift;
            // TT-nin aşağı “Təhvil verdi” hissəsində ad/səlahiyyətli şəxs heç vaxt yazılmır.
            // Hüquqi və fiziki şəxs fərq etmədən “Təhvil verdi:” ilə imza xətti arasındakı xana boş qalır.
            mappedString(s,shiftedMap,"executor.signer","B"+(bottom+2),"");
            if(or.isIndividual()){
                mappedString(s,shiftedMap,"orderer.signer","D"+(bottom+2),"");
            }else{
                mappedString(s,shiftedMap,"orderer.signer","D"+(bottom+2),or.signerLabel()+": "+or.signerName());
            }
            finalizeGeneratedWorkbook(wb,s,inv.getItems().isEmpty()?-1:start+inv.getItems().size()-1);
            save(wb,target);
        }
    }


    private void writeQrPartySignature(Sheet s, Map<String,String> map, CompanyInfo party, boolean seller, int sig){
        String col=seller?"B":"D";
        String companyKey=seller?"seller.company":"buyer.company";
        String signerKey=seller?"executor.signer":"orderer.signer";
        String companyCell=col+(sig+1), signerCell=col+(sig+3);
        // Standart QR şablonunda tərəflər solda B:C, sağda D:F sahəsini tutur.
        // Bu sahələri yalnız mapping default koordinatda qaldıqda birləşdiririk ki,
        // uzun şirkət/direktor adları həmin tam sahədə wrap olub sətri böyütsün.
        mergeQrPartyFieldIfDefault(s,map,companyKey,companyCell,seller);
        mergeQrPartyFieldIfDefault(s,map,signerKey,signerCell,seller);
        if(party!=null && party.isIndividual()){
            ExcelUtil.setString(s,col+sig,"");
            mappedString(s,map,companyKey,companyCell,"Fiziki şəxs: "+physicalPersonName(party));
            ExcelUtil.setString(s,col+(sig+2),"");
            mappedString(s,map,signerKey,signerCell,"");
            return;
        }
        ExcelUtil.setString(s,col+sig,seller?"Satıcı:":"Sifarişçi:");
        mappedString(s,map,companyKey,companyCell,party==null?"":safe(party.getCompany()));
        ExcelUtil.setString(s,col+(sig+2),(party==null?"Direktor":party.signerLabel())+":");
        mappedString(s,map,signerKey,signerCell,party==null?"":party.signerName());
    }

    private void mergeQrPartyFieldIfDefault(Sheet s,Map<String,String> map,String key,String defaultRef,boolean seller){
        if(!target(map,key,defaultRef).equalsIgnoreCase(defaultRef))return;
        int row=ExcelUtil.cell(s,defaultRef).getRowIndex();
        int from=seller?1:3, to=seller?2:5; // B:C / D:F
        CellRangeAddress wanted=new CellRangeAddress(row,row,from,to);
        for(int i=0;i<s.getNumMergedRegions();i++){
            CellRangeAddress existing=s.getMergedRegion(i);
            if(existing.formatAsString().equalsIgnoreCase(wanted.formatAsString()))return;
            if(existing.intersects(wanted))return;
        }
        s.addMergedRegion(wanted);
    }

    private String physicalPersonName(CompanyInfo party){
        if(party==null)return "";
        String name=safe(party.signerName()).trim();
        if(!name.isBlank())return name;
        return safe(party.getCompany()).replaceFirst("(?iu)^\\s*(?:F\\s*/\\s*Ş|Fiziki\\s+şəxs)\\s*:?\\s*","").trim();
    }

    private int mappedStartRow(Map<String,String> map,String key,int defaultExcelRow){
        if(map==null)return defaultExcelRow;
        String ref=map.get(key);
        if(ref==null)return defaultExcelRow;
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)^[A-Z]{1,3}([1-9][0-9]{0,4})$").matcher(ref.trim());
        if(!m.matches())return defaultExcelRow;
        try{return Math.max(1,Integer.parseInt(m.group(1)));}catch(Exception e){return defaultExcelRow;}
    }

    private Map<String,String> shiftMappingRows(Map<String,String> map,int firstShiftedExcelRow,int shift){
        if(map==null||map.isEmpty()||shift<=0)return map==null?Map.of():map;
        LinkedHashMap<String,String> out=new LinkedHashMap<>();
        for(var e:map.entrySet()){
            String ref=e.getValue();
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)^([A-Z]{1,3})([1-9][0-9]{0,4})$").matcher(safe(ref));
            if(m.matches()){int row=Integer.parseInt(m.group(2));if(row>=firstShiftedExcelRow)ref=m.group(1).toUpperCase(Locale.ROOT)+(row+shift);}
            out.put(e.getKey(),ref);
        }
        return out;
    }

    private void mappedString(Sheet s, Map<String,String> map, String key, String def, String value){ String cell=target(map,key,def); if(!cell.equalsIgnoreCase(def)) ExcelUtil.setString(s,def,""); ExcelUtil.setString(s,cell,value); }
    private void mappedNumber(Sheet s, Map<String,String> map, String key, String def, BigDecimal value){ String cell=target(map,key,def); if(!cell.equalsIgnoreCase(def)) ExcelUtil.setString(s,def,""); ExcelUtil.setNumber(s,cell,value); }
    private void mappedDate(Sheet s, Map<String,String> map, String key, String def, LocalDate value){ String cell=target(map,key,def); if(!cell.equalsIgnoreCase(def)){ ExcelUtil.setString(s,def,""); ExcelUtil.setString(s,cell,value==null?"":value.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))); } else setDate(s,cell,value); }
    private String target(Map<String,String> map,String key,String def){String v=map.get(key);return v!=null&&v.matches("(?i)[A-Z]{1,3}[1-9][0-9]{0,4}")?v.toUpperCase(Locale.ROOT):def;}

    private String contractReferenceSentence(String contractNo,String contractDate){if(blank(contractNo)&&blank(contractDate))return "Tərəflər bağlanmış müqaviləyə əsasən";if(blank(contractDate))return "Tərəflər "+contractNumberBare(contractNo)+" saylı müqaviləyə əsasən";if(blank(contractNo))return "Tərəflər "+contractDateWords(contractDate)+" tarixli müqaviləyə əsasən";return "Tərəflər "+contractDateWords(contractDate)+" tarixli "+contractNumberBare(contractNo)+" saylı müqaviləyə əsasən";}
    private String handoverIntro(CompanyInfo ex,CompanyInfo or,String contractNo,String contractDate){StringBuilder b=new StringBuilder("Biz, aşağıda imza edənlər, bu aktı tərtib edirik ondan ötrü ki, ").append(safe(or.getCompany())).append(" ilə ").append(safe(ex.getCompany())).append(" arasında bağlanmış ");if(!blank(contractDate))b.append(contractDateWords(contractDate)).append(" tarixli ");if(!blank(contractNo))b.append(contractNumberBare(contractNo)).append(" nömrəli ");return b.append("müqaviləyə əsasən aşağıda qeyd edilən ehtiyat hissələri təqdim edilmiş və xidmətlər göstərilmişdir.").toString();}
    private String noteText(String note){String x=ExcelUtil.cleanNotePrefix(note);return blank(x)?"Qeyd:":"Qeyd: "+x;}

    private int expandRows(Sheet s,int start,int totalRow,int capacity,int itemCount,int maxCol){if(itemCount<=capacity)return 0;int extra=itemCount-capacity;s.shiftRows(totalRow,s.getLastRowNum(),extra,true,false);Row source=s.getRow(start);for(int i=0;i<extra;i++){int rn=totalRow+i;Row row=s.getRow(rn);if(row==null)row=s.createRow(rn);ExcelUtil.copyStyleAndMergeSafe(source,row,maxCol);}return extra;}
    private void clearValues(Sheet s,int fromRow,int toRow,int fromCol,int toCol){for(int r=fromRow;r<=toRow;r++){Row row=s.getRow(r);if(row==null)continue;for(int c=fromCol;c<=toCol;c++){Cell cell=row.getCell(c);if(cell!=null)cell.setBlank();}}}
    private void fillItemsWithIndex(Sheet s,int start,List<InvoiceItem> items){for(int i=0;i<items.size();i++){InvoiceItem it=items.get(i);int r=start+i;ExcelUtil.setNumber(s,"A"+(r+1),BigDecimal.valueOf(i+1));ExcelUtil.setString(s,"B"+(r+1),it.name());ExcelUtil.setString(s,"C"+(r+1),it.unit());ExcelUtil.setNumber(s,"D"+(r+1),it.quantity());ExcelUtil.setNumber(s,"E"+(r+1),it.unitPrice());ExcelUtil.setNumber(s,"F"+(r+1),it.amount());}}

    /**
     * Yaradılmış sənəd saxlanmazdan əvvəl universal son layout yoxlaması aparılır.
     * Məqsəd: istifadəçi hansı Excel şablonunu seçirsə seçsin, mətn xananın içində
     * qalsın, başqa xanaya daşmasın və şablonda verilmiş sətir hündürlüyü heç vaxt
     * kiçilməsin. Uzun mətn üçün lazım olan hündürlük faylın özündə yazılır ki,
     * nəticə başqa kompüterdə və başqa Excel versiyasında da mümkün qədər eyni olsun.
     */
    private void finalizeGeneratedWorkbook(XSSFWorkbook wb, Sheet primarySheet, int lastItemRow){
        addFooterSignatureSpace(primarySheet,3);

        // Təkcə hazırda doldurulan sheet yox, şablondakı bütün görünən sheet-lər
        // son dəfə yoxlanılır. Gizli/texniki sheet-lərə toxunmuruq.
        for(int i=0;i<wb.getNumberOfSheets();i++){
            if(wb.isSheetHidden(i)||wb.isSheetVeryHidden(i))continue;
            stabilizeTextAndRows(wb,wb.getSheetAt(i));
        }
        configureA4Print(wb,primarySheet,lastItemRow);
    }

    private void addFooterSignatureSpace(Sheet s,int rows){
        if(rows<=0)return;
        int signatureRow=-1;
        for(int r=s.getLastRowNum();r>=Math.max(0,s.getFirstRowNum());r--){
            Row row=s.getRow(r); if(row==null)continue;
            boolean found=false;
            for(Cell cell:row){
                if(cell==null||cell.getCellType()!=CellType.STRING)continue;
                String x=safe(cell.getStringCellValue()).trim().replace('İ','I').replace('ı','i').toLowerCase(Locale.ROOT);
                if(x.contains("_____")||x.contains("imza")){found=true;break;}
            }
            if(found){signatureRow=r;break;}
        }
        if(signatureRow<0)return;
        int last=s.getLastRowNum();
        s.shiftRows(signatureRow,last,rows,true,false);
        float defaultHeight=s.getDefaultRowHeightInPoints()>0?s.getDefaultRowHeightInPoints():15f;
        for(int i=0;i<rows;i++){
            Row blank=s.getRow(signatureRow+i);
            if(blank==null)blank=s.createRow(signatureRow+i);
            blank.setHeightInPoints(defaultHeight);
        }
    }

    private record MergeHeightRequirement(CellRangeAddress region,double requiredHeight){}

    /**
     * Universal mətn/hündürlük sabitləşdiricisi.
     *
     * Qaydalar:
     *  - mətn tipli bütün dolu xanalarda Wrap Text aktiv edilir;
     *  - Shrink To Fit söndürülür ki, uzun mətn görünməz dərəcədə balacalaşmasın;
     *  - mövcud row height HEÇ VAXT azaldılmır, yalnız lazım olduqda artırılır;
     *  - üfüqi merge-lərdə bütün merge eni, şaquli merge-lərdə isə bütün merge
     *    sətirlərinin cəmi nəzərə alınır;
     *  - eyni sətirdə bir neçə xana varsa ən çox yer tələb edən xana qalib gəlir;
     *  - 1, 2, 3, 4, 5, 6... sətirlik mətnlər üçün standart minimum hündürlük
     *    hesablanır və nəticə faylın özündə saxlanılır.
     */
    private void stabilizeTextAndRows(XSSFWorkbook wb,Sheet s){
        DataFormatter formatter=new DataFormatter(Locale.forLanguageTag("az-AZ"));
        FormulaEvaluator evaluator=wb.getCreationHelper().createFormulaEvaluator();
        Map<Short,CellStyle> wrappedStyles=new HashMap<>();

        int last=Math.max(0,maxUsedRow(s));
        int maxCol=maxUsedColumn(s);
        float defaultHeight=s.getDefaultRowHeightInPoints()>0?s.getDefaultRowHeightInPoints():15f;
        double[] rowMinimum=new double[last+1];
        boolean[] rowHasContent=new boolean[last+1];
        List<MergeHeightRequirement> verticalMergeRequirements=new ArrayList<>();

        // Hər sətir başlanğıcda şablondakı mövcud hündürlüyünü minimum kimi saxlayır.
        // Bu, əvvəl hündür olan sətirin sonradan proqram tərəfindən kiçildilməsinin qarşısını alır.
        for(int r=0;r<=last;r++){
            Row row=s.getRow(r);
            double existing=row==null?defaultHeight:effectiveRowHeight(row,defaultHeight);
            rowMinimum[r]=Math.max(defaultHeight,existing);
        }

        for(int r=0;r<=last;r++){
            Row row=s.getRow(r);
            if(row==null)continue;
            for(int c=0;c<=maxCol;c++){
                Cell cell=row.getCell(c); if(cell==null)continue;
                String text;
                try{text=formatter.formatCellValue(cell,evaluator);}catch(Exception e){text=formatter.formatCellValue(cell);}
                if(text==null||text.isBlank())continue;
                rowHasContent[r]=true;

                CellRangeAddress merged=mergedAt(s,r,c);
                // Merge daxilində yalnız sol-yuxarı (anchor) xana real mətn sahəsidir.
                // Qalan hüceyrələri ayrıca hesablamaq eyni mətni bir neçə dəfə sayardı.
                if(merged!=null && (r!=merged.getFirstRow()||c!=merged.getFirstColumn()))continue;

                // Mətn heç vaxt qonşu xanaya daşmasın və Excel onu avtomatik kiçiltməsin.
                if(isTextCell(cell))ensureWrappedNoShrink(wb,cell,wrappedStyles);

                CellStyle style=cell.getCellStyle();
                Font font=wb.getFontAt(style.getFontIndex());
                double fontPt=font==null||font.getFontHeightInPoints()<=0?11d:font.getFontHeightInPoints();
                boolean bold=font!=null&&font.getBold();
                boolean italic=font!=null&&font.getItalic();
                double widthChars=mergedWidthChars(s,merged,c);
                // Indent vizual istifadə olunan eni azaldır.
                widthChars=Math.max(3d,widthChars-Math.max(0,style.getIndention())*2d);
                int lines=estimateLines(text,widthChars,fontPt,bold,italic);
                double required=standardRowHeight(defaultHeight,lines,fontPt);

                if(merged!=null && merged.getFirstRow()!=merged.getLastRow()){
                    verticalMergeRequirements.add(new MergeHeightRequirement(merged,required));
                }else{
                    rowMinimum[r]=Math.max(rowMinimum[r],required);
                }
            }
        }

        // Əvvəl normal və yalnız üfüqi merge olan sətirlərin minimum hündürlüklərini tətbiq et.
        for(int r=0;r<=last;r++){
            if(!rowHasContent[r])continue; // boş spacer sətirlər şablonda necədirsə elə qalır.
            Row row=s.getRow(r);
            if(row==null)row=s.createRow(r);
            if(row.getZeroHeight())continue; // şablonun qəsdən gizlətdiyi sətri açmırıq.
            double existing=effectiveRowHeight(row,defaultHeight);
            double required=Math.max(existing,rowMinimum[r]);
            row.setHeightInPoints((float)clampExcelRowHeight(required));
        }

        // Şaquli merge-lərdə tək bir row deyil, merge sahəsinin ümumi hündürlüyü vacibdir.
        // Yetmirsə əlavə hündürlük görünən sətirlər arasında bölünür.
        for(MergeHeightRequirement req:verticalMergeRequirements){
            ensureMergedRegionHeight(s,req.region(),req.requiredHeight(),defaultHeight);
        }
    }

    private boolean isTextCell(Cell cell){
        if(cell==null)return false;
        if(cell.getCellType()==CellType.STRING)return true;
        return cell.getCellType()==CellType.FORMULA && cell.getCachedFormulaResultType()==CellType.STRING;
    }

    private void ensureWrappedNoShrink(XSSFWorkbook wb,Cell cell,Map<Short,CellStyle> styleCache){
        CellStyle st=cell.getCellStyle();
        if(st.getWrapText()&&!st.getShrinkToFit())return;
        short idx=st.getIndex();
        CellStyle fixed=styleCache.get(idx);
        if(fixed==null){
            fixed=wb.createCellStyle();
            fixed.cloneStyleFrom(st);
            fixed.setWrapText(true);
            fixed.setShrinkToFit(false);
            styleCache.put(idx,fixed);
        }
        cell.setCellStyle(fixed);
    }

    private double effectiveRowHeight(Row row,double defaultHeight){
        if(row==null)return defaultHeight;
        float h=row.getHeightInPoints();
        return h>0?h:defaultHeight;
    }

    private double clampExcelRowHeight(double height){
        // OOXML/Excel row height üçün praktik maksimum 409 pt civarındadır.
        return Math.max(1d,Math.min(409d,height));
    }

    private void ensureMergedRegionHeight(Sheet s,CellRangeAddress region,double requiredHeight,double defaultHeight){
        int first=Math.max(0,region.getFirstRow());
        int last=region.getLastRow();
        if(last<first)return;

        double total=0d;
        List<Row> visibleRows=new ArrayList<>();
        for(int r=first;r<=last;r++){
            Row row=s.getRow(r);
            if(row==null)row=s.createRow(r);
            double h=effectiveRowHeight(row,defaultHeight);
            if(!row.getZeroHeight()){
                total+=h;
                visibleRows.add(row);
            }
        }
        if(total+0.25d>=requiredHeight||visibleRows.isEmpty())return;

        double remaining=requiredHeight-total;
        // Eyni merged blokun sətirlərini balanslı böyüdürük; heç birini kiçiltmirik.
        // Maksimum row-height həddinə çatan sətir varsa qalan hissə digərlərinə paylanır.
        List<Row> expandable=new ArrayList<>(visibleRows);
        while(remaining>0.25d&&!expandable.isEmpty()){
            double share=remaining/expandable.size();
            double used=0d;
            Iterator<Row> it=expandable.iterator();
            while(it.hasNext()){
                Row row=it.next();
                double current=effectiveRowHeight(row,defaultHeight);
                double room=409d-current;
                if(room<=0.25d){it.remove();continue;}
                double add=Math.min(room,share);
                row.setHeightInPoints((float)clampExcelRowHeight(current+add));
                used+=add;
                if(room-add<=0.25d)it.remove();
            }
            if(used<=0.01d)break;
            remaining-=used;
        }
    }

    private double standardRowHeight(double defaultHeight,int lines,double fontPt){
        int normalizedLines=Math.max(1,lines);
        // Times New Roman 12 pt kimi şablon fontlarında Excel-in real sətirarası
        // məsafəsinə təhlükəsiz ehtiyat əlavə olunur. 0.5 pt pilləsi eyni sətir
        // saylı mətnlər üçün eyni minimum hündürlük verir.
        double lineHeight=Math.max(16.5d,Math.ceil(Math.max(9d,fontPt)*1.45d*2d)/2d);
        double raw=normalizedLines*lineHeight+(normalizedLines==1?3d:6d);
        return Math.ceil(Math.max(defaultHeight,raw)*2d)/2d;
    }

    /**
     * Excel-in Wrap Text davranışını təxmini olaraq piksel eninə görə hesablayır.
     * Sadəcə simvol sayına baxmaq uzun, qalın və böyük hərfli şirkət adlarında
     * sətr sayını az hesablayırdı. Burada hərflərin nisbi enləri də nəzərə alınır.
     */
    private int estimateLines(String text,double widthChars,double fontPt,boolean bold,boolean italic){
        // Excel sütun eni Calibri-11 simvol vahididir: təxminən 7 px. Təhlükəsizlik
        // ehtiyatı border/padding və Excel/LibreOffice render fərqini kompensasiya edir.
        double limitPx=Math.max(24d,widthChars*7d*0.78d-12d);
        double spacePx=approxTextWidthPx(" ",fontPt,bold,italic);
        int total=0;
        for(String physical:safe(text).replace("\r","").split("\n",-1)){
            if(physical.isEmpty()){total++;continue;}
            String trimmed=physical.trim();
            if(trimmed.isEmpty()){total++;continue;}
            int lines=1;
            double current=0d;
            for(String word:trimmed.split("\\s+")){
                if(word.isEmpty())continue;
                double wordPx=approxTextWidthPx(word,fontPt,bold,italic);
                if(wordPx<=limitPx){
                    if(current<=0d)current=wordPx;
                    else if(current+spacePx+wordPx<=limitPx)current+=spacePx+wordPx;
                    else{lines++;current=wordPx;}
                    continue;
                }

                // Çox uzun söz/kod ayrıca simvollara bölünərək wrap edilir.
                if(current>0d){lines++;current=0d;}
                for(int i=0;i<word.length();i++){
                    double charPx=approxTextWidthPx(String.valueOf(word.charAt(i)),fontPt,bold,italic);
                    if(current>0d && current+charPx>limitPx){lines++;current=0d;}
                    current+=charPx;
                }
            }
            total+=lines;
        }
        return Math.max(1,total);
    }

    private double approxTextWidthPx(String text,double fontPt,boolean bold,boolean italic){
        if(text==null||text.isEmpty())return 0d;
        double emPx=Math.max(8d,fontPt)*96d/72d;
        double units=0d;
        for(int i=0;i<text.length();i++)units+=charWidthFactor(text.charAt(i));
        double styleFactor=(bold?1.055d:1d)*(italic?1.025d:1d);
        return units*emPx*styleFactor;
    }

    private double charWidthFactor(char ch){
        if(Character.isWhitespace(ch))return 0.31d;
        if("ilIıİjjtfr.,:;!'`|".indexOf(ch)>=0)return 0.30d;
        if("MWƏWQOĞŞÜÖÇ@%&".indexOf(ch)>=0)return 0.82d;
        if("mwəğşüöç".indexOf(ch)>=0)return 0.73d;
        if(Character.isUpperCase(ch))return 0.66d;
        if(Character.isLowerCase(ch))return 0.50d;
        if(Character.isDigit(ch))return 0.55d;
        return 0.42d;
    }

    private CellRangeAddress mergedAt(Sheet s,int row,int col){
        for(int i=0;i<s.getNumMergedRegions();i++){
            CellRangeAddress m=s.getMergedRegion(i);
            if(m.isInRange(row,col))return m;
        }
        return null;
    }

    private double mergedWidthChars(Sheet s,CellRangeAddress merged,int col){
        if(merged==null)return Math.max(4d,s.getColumnWidth(col)/256d);
        double w=0;
        for(int c=merged.getFirstColumn();c<=merged.getLastColumn();c++)if(!s.isColumnHidden(c))w+=Math.max(1d,s.getColumnWidth(c)/256d);
        return Math.max(4d,w);
    }

    private int maxUsedRow(Sheet s){
        int max=Math.max(0,s.getLastRowNum());
        for(int i=0;i<s.getNumMergedRegions();i++)max=Math.max(max,s.getMergedRegion(i).getLastRow());
        return max;
    }

    private int maxUsedColumn(Sheet s){
        int max=0;
        for(Row row:s)if(row!=null&&row.getLastCellNum()>0)max=Math.max(max,row.getLastCellNum()-1);
        for(int i=0;i<s.getNumMergedRegions();i++)max=Math.max(max,s.getMergedRegion(i).getLastColumn());
        return max;
    }

    private void configureA4Print(XSSFWorkbook wb,Sheet s,int lastItemRow){
        int minCol=Integer.MAX_VALUE,maxCol=-1,lastRow=-1;
        DataFormatter f=new DataFormatter();
        FormulaEvaluator ev=wb.getCreationHelper().createFormulaEvaluator();
        for(Row row:s){
            if(row==null)continue;
            for(Cell cell:row){
                if(cell==null)continue;
                String txt; try{txt=f.formatCellValue(cell,ev);}catch(Exception e){txt=f.formatCellValue(cell);}
                // Şablonda G/H kimi yalnız texniki style daşıyan boş hüceyrələr ola bilər.
                // Çap sahəsini yalnız real məzmun + merge-lər əsasında qururuq ki,
                // A:F sənəd eni lazımsız yerə daralmasın.
                if(safe(txt).isBlank())continue;
                minCol=Math.min(minCol,cell.getColumnIndex());maxCol=Math.max(maxCol,cell.getColumnIndex());lastRow=Math.max(lastRow,row.getRowNum());
            }
        }
        for(int i=0;i<s.getNumMergedRegions();i++){
            CellRangeAddress m=s.getMergedRegion(i);minCol=Math.min(minCol,m.getFirstColumn());maxCol=Math.max(maxCol,m.getLastColumn());lastRow=Math.max(lastRow,m.getLastRow());
        }
        if(minCol==Integer.MAX_VALUE){minCol=0;maxCol=Math.max(0,maxUsedColumn(s));lastRow=Math.max(0,s.getLastRowNum());}
        wb.setPrintArea(wb.getSheetIndex(s),minCol,maxCol,0,Math.max(0,lastRow));
        s.setAutobreaks(true);
        s.setFitToPage(true);
        s.setPrintGridlines(false);
        s.setHorizontallyCenter(true);
        s.setVerticallyCenter(false);
        PrintSetup ps=s.getPrintSetup();
        ps.setPaperSize(PrintSetup.A4_PAPERSIZE);
        ps.setLandscape(false);
        ps.setFitWidth((short)1);
        s.setMargin(Sheet.LeftMargin,0.22);
        s.setMargin(Sheet.RightMargin,0.22);
        s.setMargin(Sheet.TopMargin,0.30);
        s.setMargin(Sheet.BottomMargin,0.30);
        s.setMargin(Sheet.HeaderMargin,0.15);
        s.setMargin(Sheet.FooterMargin,0.15);
        // Şaquli sıxma yalnız məhsulların hamısı birinci A4 səhifəsinə sığdığı,
        // lakin yekun/qeyd/imza hissəsinin növbəti səhifəyə daşdığı halda aktivləşir.
        // Son məhsul sətrinin özü artıq ikinci səhifəyə düşürsə, hündürlük AUTO qalır.
        ps.setFitHeight(shouldFitFooterBackToFirstPage(s,minCol,maxCol,lastRow,lastItemRow) ? (short)1 : (short)0);
    }


    private boolean shouldFitFooterBackToFirstPage(Sheet s,int minCol,int maxCol,int lastRow,int lastItemRow){
        if(lastItemRow<0||lastItemRow>lastRow)return false;

        // A4 portrait: 210 x 297 mm. Excel margin-ləri inch ilə saxlayır.
        double pageWidthPt=210d/25.4d*72d;
        double pageHeightPt=297d/25.4d*72d;
        double printableWidthPt=pageWidthPt-(s.getMargin(Sheet.LeftMargin)+s.getMargin(Sheet.RightMargin))*72d;
        double printableHeightPt=pageHeightPt-(s.getMargin(Sheet.TopMargin)+s.getMargin(Sheet.BottomMargin))*72d;
        if(printableWidthPt<=0||printableHeightPt<=0)return false;

        double contentWidthPt=0d;
        for(int c=Math.max(0,minCol);c<=Math.max(minCol,maxCol);c++){
            if(s.isColumnHidden(c))continue;
            // Excel sütun eni təxminən simvol vahidindədir; Calibri 11 üçün 1 vahid ~7 px.
            double chars=Math.max(1d,s.getColumnWidth(c)/256d);
            contentWidthPt+=chars*7d*72d/96d;
        }
        double widthScale=contentWidthPt<=0?1d:Math.min(1d,printableWidthPt/contentWidthPt);

        double itemBottomPt=0d,totalHeightPt=0d;
        for(int r=0;r<=Math.max(0,lastRow);r++){
            Row row=s.getRow(r);
            if(row!=null&&row.getZeroHeight())continue;
            double h=row==null||row.getHeightInPoints()<=0?s.getDefaultRowHeightInPoints():row.getHeightInPoints();
            h=Math.max(0d,h);
            totalHeightPt+=h;
            if(r<=lastItemRow)itemBottomPt+=h;
        }

        double scaledItemBottom=itemBottomPt*widthScale;
        double scaledTotal=totalHeightPt*widthScale;
        double tolerance=3d; // printer/Excel yuvarlaqlaşdırmasına görə kiçik ehtiyat

        boolean itemsFitFirstPage=scaledItemBottom<=printableHeightPt+tolerance;
        boolean footerWouldSpill=scaledTotal>printableHeightPt+tolerance;
        return itemsFitFirstPage&&footerWouldSpill;
    }

    private void waitGeneration(Future<?> future) throws IOException {
        try { future.get(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Sənəd yaradılması dayandırıldı.", e); }
        catch (ExecutionException e) { Throwable c=e.getCause(); if(c instanceof UncheckedIOException u) throw u.getCause(); if(c instanceof RuntimeException r) throw r; throw new IOException("Sənəd yaradılması zamanı xəta.", c); }
    }

    private XSSFWorkbook openTemplate(String ws,String type,String customUploadId,String entityType)throws IOException{
        String cacheKey="standard|"+type.toLowerCase(Locale.ROOT)+"|"+safe(entityType);
        byte[] bytes=templateBytesCache.get(cacheKey);
        if(bytes==null){
            try{bytes=new ClassPathResource("excel-templates/"+defaultName(type,entityType)).getInputStream().readAllBytes();templateBytesCache.put(cacheKey,bytes);}
            catch(Exception e){throw new IllegalArgumentException("Standart sənəd şablonu düzgün .xlsx faylı deyil: "+e.getMessage());}
        }
        try{return new XSSFWorkbook(new ByteArrayInputStream(bytes));}catch(Exception e){throw new IllegalArgumentException("Standart sənəd şablonu açıla bilmədi: "+e.getMessage());}
    }
    private String defaultName(String type){return defaultName(type,CompanyInfo.LEGAL);}
    private String defaultName(String type,String entityType){
        boolean individual=CompanyInfo.INDIVIDUAL.equalsIgnoreCase(entityType);
        return switch(type.toLowerCase(Locale.ROOT)){case "hf"->"hesab_faktura_faktiki.xlsx";case "qr"->individual?"qiymet_razilasma_fiziki.xlsx":"qiymet_razilasma_faktiki.xlsx";case "tt"->individual?"tehvil_teslim_fiziki.xlsx":"tehvil_teslim_faktiki.xlsx";default->throw new IllegalArgumentException("Şablon tipi hf, qr və ya tt olmalıdır.");};
    }
    private Sheet templateSheet(XSSFWorkbook wb){Sheet s=wb.getSheet("ŞABLON");if(s==null&&wb.getNumberOfSheets()>0)s=wb.getSheetAt(0);if(s==null)throw new IllegalArgumentException("Şablonda iş vərəqi tapılmadı.");return s;}
    private void save(Workbook wb,Path p)throws IOException{try(OutputStream out=Files.newOutputStream(p)){wb.write(out);}}
    private void setDate(Sheet s,String ref,LocalDate d){ExcelUtil.cell(s,ref).setCellValue(java.sql.Date.valueOf(d));}
    private CompanyInfo findCompany(List<CompanyInfo> list,String id,String label){return list.stream().filter(c->Objects.equals(c.getId(),id)).findFirst().orElseThrow(()->new IllegalArgumentException(label+" şirkəti seçilməyib və ya tapılmadı."));}
    private void validateCompany(CompanyInfo c,String label){if(c==null||blank(c.getCompany()))throw new IllegalArgumentException(label+" tərəfin adı boş ola bilməz.");}
    private LocalDate parseDate(String s,LocalDate fallback){if(blank(s))return fallback==null?LocalDate.now():fallback;try{return LocalDate.parse(s);}catch(Exception e){return fallback==null?LocalDate.now():fallback;}}
    private String safeNo(String s){return blank(s)?"000":s.trim();}
    private String contractNumberText(String raw){if(blank(raw))return "";return "№ "+raw.trim().replaceFirst("^№\\s*","");}
    private String contractNumberBare(String raw){return blank(raw)?"":raw.trim().replaceFirst("^№\\s*","");}
    private String contractDateWords(String raw){if(blank(raw))return "";for(String p:List.of("dd.MM.yyyy","d.M.yyyy","dd/MM/yyyy","d/MM/yyyy","yyyy-MM-dd")){try{return azDate(LocalDate.parse(raw,DateTimeFormatter.ofPattern(p)));}catch(Exception ignored){}}return raw;}
    private String azDate(LocalDate d){String[]m={"","Yanvar","Fevral","Mart","Aprel","May","İyun","İyul","Avqust","Sentyabr","Oktyabr","Noyabr","Dekabr"};return String.format("%02d %s %d-ci il",d.getDayOfMonth(),m[d.getMonthValue()],d.getYear());}
    private String firstNonBlank(String first,String second){return !blank(first)?first.trim():safe(second).trim();}
    private String safe(String s){return s==null?"":s;}
    private boolean blank(String s){return s==null||s.isBlank();}
    private String prefixed(String prefix,String value){return blank(value)?prefix.trim():prefix+value.trim();}
    private String sanitize(String s){return safe(s).replaceAll("[^A-Za-z0-9_-]","_");}
    private void zipFolder(Path folder,Path zip)throws IOException{try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(zip));var stream=Files.list(folder)){z.setLevel(java.util.zip.Deflater.BEST_SPEED);for(Path p:stream.filter(Files::isRegularFile).filter(p->!p.equals(zip)).filter(p->!p.getFileName().toString().equals("manifest.json")).toList()){z.putNextEntry(new ZipEntry(p.getFileName().toString()));Files.copy(p,z);z.closeEntry();}}}
}
