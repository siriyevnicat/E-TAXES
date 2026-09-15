package az.gmb.taxdata.service;

import az.gmb.taxdata.model.*;
import az.gmb.taxdata.util.ExcelUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

@Service
public class WorkspaceDataService {
    private final StorageService storage;
    private final ObjectMapper mapper;
    private final CompanyExcelService companyExcelService;

    public WorkspaceDataService(StorageService storage, ObjectMapper mapper, CompanyExcelService companyExcelService) {
        this.storage = storage;
        this.mapper = mapper;
        this.companyExcelService = companyExcelService;
    }

    public synchronized WorkspaceState getState(String workspaceId) throws IOException {
        Path file = stateFile(workspaceId);
        if (!Files.exists(file)) {
            WorkspaceState seeded = new WorkspaceState();
            // Hər yeni istifadəçi/workspace boş şəxsi baza ilə başlayır.
            // Mövcud workspacelərin məlumatları olduğu kimi qorunur; lazım olsa ZIP import edilə bilər.
            seeded.setCompanies(new ArrayList<>());
            saveState(workspaceId, seeded);
            return seeded;
        }
        WorkspaceState state = mapper.readValue(file.toFile(), WorkspaceState.class);
        normalize(state);
        return state;
    }

    public synchronized void saveState(String workspaceId, WorkspaceState state) throws IOException {
        normalize(state);
        Path file = stateFile(workspaceId);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------------- Companies ----------------
    public List<CompanyInfo> getCompanies(String workspaceId) throws IOException {
        return new ArrayList<>(getState(workspaceId).getCompanies());
    }

    public CompanyInfo getCompany(String workspaceId, String id) throws IOException {
        return getState(workspaceId).getCompanies().stream().filter(c -> Objects.equals(c.getId(), id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Şirkət tapılmadı: " + id));
    }

    /**
     * Excel importu yalnız yeni şirkətləri əlavə edir. Eyni VÖEN varsa təkrar yazılmır;
     * VÖEN boşdursa şirkət/ad soyad üzrə müqayisə olunur.
     */
    public synchronized CompanyImportResult importCompanies(String workspaceId, Collection<CompanyInfo> incoming) throws IOException {
        WorkspaceState state = getState(workspaceId);
        LinkedHashMap<String, CompanyInfo> existing = new LinkedHashMap<>();
        for (CompanyInfo c : state.getCompanies()) existing.put(companyIdentity(c), c);
        int added = 0, skipped = 0;
        for (CompanyInfo c : incoming == null ? List.<CompanyInfo>of() : incoming) {
            String key = companyIdentity(c);
            if (key.isBlank() || existing.containsKey(key)) { skipped++; continue; }
            if (c.getId() == null || c.getId().isBlank()) c.setId("USER_" + UUID.randomUUID());
            existing.put(key, c); added++;
        }
        state.setCompanies(new ArrayList<>(existing.values()));
        saveState(workspaceId, state);
        return new CompanyImportResult(incoming == null ? 0 : incoming.size(), added, skipped, new ArrayList<>(state.getCompanies()));
    }

    /** Compatibility for older code. */
    public synchronized List<CompanyInfo> mergeCompanies(String workspaceId, Collection<CompanyInfo> incoming) throws IOException {
        return importCompanies(workspaceId, incoming).companies();
    }

    public synchronized CompanyInfo saveCompany(String workspaceId, CompanyInfo company) throws IOException {
        if (company == null || company.getCompany() == null || company.getCompany().isBlank())
            throw new IllegalArgumentException("Şirkət / fiziki şəxs adı boş ola bilməz.");
        WorkspaceState state = getState(workspaceId);
        List<CompanyInfo> list = new ArrayList<>(state.getCompanies());
        String identity = companyIdentity(company);
        if (company.getId() != null && !company.getId().isBlank()) {
            for (CompanyInfo old : list) {
                if (!Objects.equals(old.getId(), company.getId()) && companyIdentity(old).equals(identity))
                    throw new IllegalArgumentException("Bu VÖEN / şirkət artıq başqa rekvizit qeydində mövcuddur.");
            }
            for (int i = 0; i < list.size(); i++) {
                if (Objects.equals(list.get(i).getId(), company.getId())) {
                    list.set(i, company); state.setCompanies(list); saveState(workspaceId, state); return company;
                }
            }
        }
        for (CompanyInfo old : list) {
            if (companyIdentity(old).equals(identity))
                throw new IllegalArgumentException("Bu şirkət / fiziki şəxs artıq bazada mövcuddur.");
        }
        company.setId("USER_" + UUID.randomUUID());
        list.add(company); state.setCompanies(list); saveState(workspaceId, state); return company;
    }

    public synchronized void deleteCompany(String workspaceId, String id) throws IOException {
        WorkspaceState state = getState(workspaceId);
        boolean removed = state.getCompanies().removeIf(c -> Objects.equals(c.getId(), id));
        if (!removed) throw new IllegalArgumentException("Şirkət tapılmadı.");
        saveState(workspaceId, state);
    }

    private List<CompanyInfo> dedupeCompanies(List<CompanyInfo> list) {
        LinkedHashMap<String, CompanyInfo> map = new LinkedHashMap<>();
        for (CompanyInfo c : list) map.put(companyIdentity(c), c);
        return new ArrayList<>(map.values());
    }

    private String companyIdentity(CompanyInfo c) {
        if (c == null) return "";
        String voen = c.getVoen() == null ? "" : c.getVoen().replaceAll("\\D", "");
        if (!voen.isBlank()) return "voen:" + voen;
        String name = normalizeKey(c.getCompany());
        return name.isBlank() ? "" : "name:" + name;
    }

    // ---------------- Invoice registry ----------------
    public List<InvoiceSummary> getInvoices(String workspaceId) throws IOException {
        List<InvoiceSummary> out = getState(workspaceId).getInvoices().stream().map(StoredInvoice::toSummary).toList();
        return out.stream().sorted(Comparator.comparing(InvoiceSummary::invoiceDate,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(InvoiceSummary::eInvoiceNumber,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
    }

    public synchronized InvoiceImportResult importInvoices(String workspaceId, String uploadId, Collection<InvoiceSummary> parsed) throws IOException {
        WorkspaceState state = getState(workspaceId);
        LinkedHashMap<String, StoredInvoice> map = new LinkedHashMap<>();
        for (StoredInvoice x : state.getInvoices()) map.put(invoiceKey(x.getInvoiceNumber()), x);
        int added = 0, skipped = 0;
        for (InvoiceSummary s : parsed == null ? List.<InvoiceSummary>of() : parsed) {
            String key = invoiceKey(s.eInvoiceNumber());
            if (key.isBlank()) { skipped++; continue; }
            StoredInvoice existingInvoice = map.get(key);
            if (existingInvoice != null) {
                // Köhnə versiyada import olunmuş qaimələrdə Alıcı/Satıcı boş qala bilərdi.
                // Eyni qaimə yenidən Excel-dən gələndə registri təkrarlamadan çatışmayan metadata tamamlanır.
                boolean changed = false;
                if (blank(existingInvoice.getBuyerName()) && !blank(s.buyerName())) { existingInvoice.setBuyerName(s.buyerName()); changed = true; }
                if (blank(existingInvoice.getBuyerVoen()) && !blank(s.buyerVoen())) { existingInvoice.setBuyerVoen(s.buyerVoen()); changed = true; }
                if (blank(existingInvoice.getSellerName()) && !blank(s.sellerName())) { existingInvoice.setSellerName(s.sellerName()); changed = true; }
                if (blank(existingInvoice.getSellerVoen()) && !blank(s.sellerVoen())) { existingInvoice.setSellerVoen(s.sellerVoen()); changed = true; }
                if (blank(existingInvoice.getNote()) && !blank(s.note())) { existingInvoice.setNote(ExcelUtil.cleanNotePrefix(s.note())); changed = true; }
                if (existingInvoice.getInvoiceDate() == null && s.invoiceDate() != null) { existingInvoice.setInvoiceDate(s.invoiceDate()); changed = true; }
                if (blank(existingInvoice.getMtNumber()) && !blank(s.mtNumber())) { existingInvoice.setMtNumber(s.mtNumber()); changed = true; }
                if (blank(existingInvoice.getSourceUploadId()) && !blank(uploadId) && !existingInvoice.isManual()) { existingInvoice.setSourceUploadId(uploadId); changed = true; }
                skipped++;
                continue;
            }
            StoredInvoice x = StoredInvoice.fromSummary(s, uploadId);
            x.setId("INV_" + UUID.randomUUID());
            x.setNote(ExcelUtil.cleanNotePrefix(x.getNote()));
            map.put(key, x); added++;
        }
        state.setInvoices(new ArrayList<>(map.values()));
        saveState(workspaceId, state);
        return new InvoiceImportResult(uploadId, parsed == null ? 0 : parsed.size(), added, skipped, false, getInvoices(workspaceId));
    }

    public StoredInvoice getStoredInvoice(String workspaceId, String invoiceNumber) throws IOException {
        String key = invoiceKey(invoiceNumber);
        return getState(workspaceId).getInvoices().stream()
                .filter(x -> invoiceKey(x.getInvoiceNumber()).equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Qaimə bazada tapılmadı: " + invoiceNumber));
    }

    public synchronized StoredInvoice saveManualInvoice(String workspaceId, String originalInvoiceNumber, InvoiceData data) throws IOException {
        if (data == null || data.getEInvoiceNumber() == null || data.getEInvoiceNumber().isBlank())
            throw new IllegalArgumentException("Qaimə nömrəsi boş ola bilməz.");
        normalizeManualInvoice(data);
        WorkspaceState state = getState(workspaceId);
        String oldKey = invoiceKey(originalInvoiceNumber);
        String newKey = invoiceKey(data.getEInvoiceNumber());
        StoredInvoice existing = null;
        for (StoredInvoice x : state.getInvoices()) {
            if ((!oldKey.isBlank() && invoiceKey(x.getInvoiceNumber()).equals(oldKey)) || invoiceKey(x.getInvoiceNumber()).equals(newKey)) {
                existing = x; break;
            }
        }
        if (existing == null) { existing = new StoredInvoice(); existing.setId("INV_" + UUID.randomUUID()); state.getInvoices().add(existing); }
        else if (!oldKey.isBlank() && !oldKey.equals(newKey)) {
            for (StoredInvoice x : state.getInvoices()) {
                if (x != existing && invoiceKey(x.getInvoiceNumber()).equals(newKey))
                    throw new IllegalArgumentException("Yeni qaimə nömrəsi artıq bazada mövcuddur.");
            }
        }
        existing.setInvoiceNumber(data.getEInvoiceNumber().trim());
        existing.setMtNumber(blank(data.getMtNumber()) ? data.getEInvoiceNumber().trim() : data.getMtNumber().trim());
        existing.setInvoiceDate(data.getInvoiceDate()); existing.setBuyerName(safe(data.getBuyerName()).trim()); existing.setBuyerVoen(safe(data.getBuyerVoen()).trim()); existing.setSellerName(safe(data.getSellerName()).trim()); existing.setSellerVoen(safe(data.getSellerVoen()).trim());
        existing.setItemCount(data.getItems().size()); existing.setSubtotal(data.getSubtotal()); existing.setVat(data.getVat()); existing.setTotal(data.getTotal());
        existing.setVatLabel(data.getVatLabel()); existing.setNote(data.getNote()); existing.setSourceSheet("Əl ilə"); existing.setSourceUploadId(""); existing.setManual(true); existing.setManualData(data);
        saveState(workspaceId, state); return existing;
    }

    public synchronized void deleteInvoice(String workspaceId, String invoiceNumber) throws IOException {
        WorkspaceState state = getState(workspaceId);
        String key = invoiceKey(invoiceNumber);
        boolean removed = state.getInvoices().removeIf(x -> invoiceKey(x.getInvoiceNumber()).equals(key));
        if (!removed) throw new IllegalArgumentException("Qaimə tapılmadı.");
        saveState(workspaceId, state);
    }

    private void normalizeManualInvoice(InvoiceData d) {
        List<InvoiceItem> fixed = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (InvoiceItem it : d.getItems()) {
            if (it == null || blank(it.name())) continue;
            BigDecimal qty = it.quantity() == null ? BigDecimal.ONE : it.quantity();
            BigDecimal price = it.unitPrice() == null ? BigDecimal.ZERO : it.unitPrice();
            BigDecimal amount = it.amount() == null ? BigDecimal.ZERO : it.amount();
            if (amount.signum() == 0 && qty.signum() != 0 && price.signum() != 0) amount = qty.multiply(price);
            amount = amount.setScale(2, RoundingMode.HALF_UP);
            fixed.add(new InvoiceItem(it.name().trim(), blank(it.unit()) ? "ədəd" : it.unit().trim(), qty, price, amount)); subtotal = subtotal.add(amount);
        }
        d.getItems().clear(); d.getItems().addAll(fixed);
        BigDecimal vat = d.getVat() == null ? BigDecimal.ZERO : d.getVat().max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        d.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP)); d.setVat(vat); d.setTotal(d.getSubtotal().add(vat));
        d.setVatMode("AUTO"); d.setVatLabel(vat.signum() > 0 ? "+18% (ƏDV)" : "ƏDV-siz");
        d.setNote(ExcelUtil.cleanNotePrefix(d.getNote())); if (d.getInvoiceDate() == null) d.setInvoiceDate(LocalDate.now());
        if (blank(d.getMtNumber())) d.setMtNumber(d.getEInvoiceNumber());
    }

    public String importedUploadForHash(String workspaceId, String hash) throws IOException {
        return getState(workspaceId).getInvoiceFileHashes().getOrDefault(hash, "");
    }
    public List<InvoiceSummary> cachedInvoiceSummaries(String workspaceId, String hash) throws IOException {
        List<InvoiceSummary> cached = getState(workspaceId).getInvoiceImportCache().get(hash);
        return cached == null ? List.of() : new ArrayList<>(cached);
    }
    public int invoiceImportCacheVersion(String workspaceId) throws IOException { return getState(workspaceId).getInvoiceImportCacheVersion(); }
    public synchronized void recordInvoiceFileImport(String workspaceId, String hash, String uploadId, Collection<InvoiceSummary> parsed) throws IOException {
        WorkspaceState state = getState(workspaceId);
        state.getInvoiceFileHashes().put(hash, uploadId);
        state.getInvoiceImportCache().put(hash, parsed == null ? new ArrayList<>() : new ArrayList<>(parsed));
        state.setInvoiceImportCacheVersion(2); // v2: Qəbul edən/Göndərən ad+VÖEN metadata cache-də saxlanılır.
        saveState(workspaceId, state);
    }
    /** Köhnə çağırışlarla uyğunluq üçün. */
    public synchronized void recordInvoiceFileHash(String workspaceId, String hash, String uploadId) throws IOException {
        recordInvoiceFileImport(workspaceId, hash, uploadId, List.of());
    }

    public String importedOutgoingUploadForHash(String workspaceId,String hash) throws IOException {
        return getState(workspaceId).getOutgoingFileHashes().getOrDefault(hash,"");
    }
    public synchronized void recordOutgoingFileHash(String workspaceId,String hash,String uploadId) throws IOException {
        WorkspaceState state=getState(workspaceId);state.getOutgoingFileHashes().put(hash,uploadId);saveState(workspaceId,state);
    }

    private String invoiceKey(String s) { return normalizeKey(s); }
    private String normalizeKey(String s) { return safe(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9əöüğışç]", ""); }

    // ---------------- Templates / print ----------------
    public String activeTemplate(String workspaceId, String type, String entityType) throws IOException {
        return getState(workspaceId).getActiveTemplates().getOrDefault(templateKey(type, entityType), "");
    }
    public synchronized void setActiveTemplate(String workspaceId, String type, String entityType, String uploadId) throws IOException {
        WorkspaceState state = getState(workspaceId); String key = templateKey(type, entityType);
        if (uploadId == null || uploadId.isBlank()) state.getActiveTemplates().remove(key); else state.getActiveTemplates().put(key, uploadId);
        saveState(workspaceId, state);
    }
    public Map<String,String> mapping(String workspaceId, String type, String entityType) throws IOException {
        return new LinkedHashMap<>(getState(workspaceId).getTemplateMappings().getOrDefault(templateKey(type, entityType), Map.of()));
    }
    public synchronized void saveMapping(String workspaceId, String type, String entityType, Map<String,String> mapping) throws IOException {
        WorkspaceState state = getState(workspaceId); LinkedHashMap<String,String> clean = new LinkedHashMap<>();
        if (mapping != null) mapping.forEach((k,v) -> { if (k != null && !k.isBlank() && v != null && v.matches("(?i)[A-Z]{1,3}[1-9][0-9]{0,4}")) clean.put(k, v.toUpperCase(Locale.ROOT)); });
        state.getTemplateMappings().put(templateKey(type, entityType), clean); saveState(workspaceId, state);
    }
    public synchronized void resetTemplate(String workspaceId, String type, String entityType) throws IOException {
        WorkspaceState state = getState(workspaceId); String key = templateKey(type, entityType);
        state.getActiveTemplates().remove(key); state.getTemplateMappings().remove(key); saveState(workspaceId, state);
    }

    public Map<String,Integer> printCopies(String workspaceId) throws IOException { return new LinkedHashMap<>(getState(workspaceId).getPrintCopies()); }
    public synchronized void savePrintCopies(String workspaceId, Map<String,Integer> copies) throws IOException {
        WorkspaceState state = getState(workspaceId);
        for (String key : List.of("hf","tt","qr")) { int value = copies == null ? state.getPrintCopies().getOrDefault(key, 1) : copies.getOrDefault(key, state.getPrintCopies().getOrDefault(key, 1)); state.getPrintCopies().put(key, Math.max(0, Math.min(value, 20))); }
        saveState(workspaceId, state);
    }

    private String templateKey(String type, String entityType) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT); String e = CompanyInfo.INDIVIDUAL.equalsIgnoreCase(entityType) ? CompanyInfo.INDIVIDUAL : CompanyInfo.LEGAL; return e + "." + t;
    }
    private Path stateFile(String workspaceId) throws IOException { return storage.getWorkspaceDir(workspaceId).resolve("workspace-state.json"); }
    private void normalize(WorkspaceState state) {
        if (state.getCompanies() == null) state.setCompanies(new ArrayList<>());
        if (state.getInvoices() == null) state.setInvoices(new ArrayList<>());
        for (StoredInvoice invoice : state.getInvoices()) {
            if (invoice == null) continue;
            invoice.setNote(ExcelUtil.cleanNotePrefix(invoice.getNote()));
            if (invoice.getManualData() != null)
                invoice.getManualData().setNote(ExcelUtil.cleanNotePrefix(invoice.getManualData().getNote()));
        }
        if (state.getInvoiceFileHashes() == null) state.setInvoiceFileHashes(new LinkedHashMap<>());
        if (state.getInvoiceImportCache() == null) state.setInvoiceImportCache(new LinkedHashMap<>());
        if (state.getOutgoingInvoices() == null) state.setOutgoingInvoices(new ArrayList<>());
        for (OutgoingInvoice invoice : state.getOutgoingInvoices())
            if (invoice != null) invoice.setNote(ExcelUtil.cleanNotePrefix(invoice.getNote()));
        if (state.getOutgoingFileHashes() == null) state.setOutgoingFileHashes(new LinkedHashMap<>());
        if (state.getContracts() == null) state.setContracts(new ArrayList<>());
        if (state.getGeneratedDocuments() == null) state.setGeneratedDocuments(new ArrayList<>());
        if (state.getContractTemplateUploadId() == null) state.setContractTemplateUploadId("");
        if (state.getActiveTemplates() == null) state.setActiveTemplates(new LinkedHashMap<>());
        if (state.getTemplateMappings() == null) state.setTemplateMappings(new LinkedHashMap<>());
        if (state.getPrintCopies() == null) state.setPrintCopies(new LinkedHashMap<>());
    }
    private boolean blank(String s){ return s == null || s.isBlank(); }
    private String safe(String s){ return s == null ? "" : s; }
}
