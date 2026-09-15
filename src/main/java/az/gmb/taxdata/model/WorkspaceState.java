package az.gmb.taxdata.model;

import java.util.*;

public class WorkspaceState {
    private List<CompanyInfo> companies = new ArrayList<>();
    private List<StoredInvoice> invoices = new ArrayList<>();
    private Map<String, String> invoiceFileHashes = new LinkedHashMap<>();
    // Fayl hash-i üzrə əvvəl oxunmuş qaimə siyahısı. Eyni Excel yenidən seçiləndə
    // ağır parse təkrarlanmır, amma registrdən silinmiş qaimə varsa cache-dən yenidən əlavə oluna bilir.
    private Map<String, List<InvoiceSummary>> invoiceImportCache = new LinkedHashMap<>();
    private int invoiceImportCacheVersion = 0;
    private List<OutgoingInvoice> outgoingInvoices = new ArrayList<>();
    private Map<String, String> outgoingFileHashes = new LinkedHashMap<>();
    private List<ContractRecord> contracts = new ArrayList<>();
    private List<GenerationArchiveRecord> generatedDocuments = new ArrayList<>();
    private String contractTemplateUploadId = "";
    private Map<String, String> activeTemplates = new LinkedHashMap<>();
    private Map<String, Map<String, String>> templateMappings = new LinkedHashMap<>();
    private Map<String, Integer> printCopies = new LinkedHashMap<>(Map.of("hf", 1, "tt", 2, "qr", 2));

    public List<CompanyInfo> getCompanies() { return companies; }
    public void setCompanies(List<CompanyInfo> companies) { this.companies = companies == null ? new ArrayList<>() : companies; }
    public List<StoredInvoice> getInvoices() { return invoices; }
    public void setInvoices(List<StoredInvoice> invoices) { this.invoices = invoices == null ? new ArrayList<>() : invoices; }
    public Map<String, String> getInvoiceFileHashes() { return invoiceFileHashes; }
    public void setInvoiceFileHashes(Map<String, String> invoiceFileHashes) { this.invoiceFileHashes = invoiceFileHashes == null ? new LinkedHashMap<>() : invoiceFileHashes; }
    public Map<String, List<InvoiceSummary>> getInvoiceImportCache() { return invoiceImportCache; }
    public void setInvoiceImportCache(Map<String, List<InvoiceSummary>> invoiceImportCache) { this.invoiceImportCache = invoiceImportCache == null ? new LinkedHashMap<>() : invoiceImportCache; }
    public int getInvoiceImportCacheVersion() { return invoiceImportCacheVersion; }
    public void setInvoiceImportCacheVersion(int v) { invoiceImportCacheVersion = v; }
    public List<OutgoingInvoice> getOutgoingInvoices() { return outgoingInvoices; }
    public void setOutgoingInvoices(List<OutgoingInvoice> v) { outgoingInvoices = v == null ? new ArrayList<>() : v; }
    public Map<String,String> getOutgoingFileHashes() { return outgoingFileHashes; }
    public void setOutgoingFileHashes(Map<String,String> v) { outgoingFileHashes = v == null ? new LinkedHashMap<>() : v; }
    public List<ContractRecord> getContracts() { return contracts; }
    public void setContracts(List<ContractRecord> v) { contracts = v == null ? new ArrayList<>() : v; }
    public List<GenerationArchiveRecord> getGeneratedDocuments() { return generatedDocuments; }
    public void setGeneratedDocuments(List<GenerationArchiveRecord> v) { generatedDocuments = v == null ? new ArrayList<>() : v; }
    public String getContractTemplateUploadId() { return contractTemplateUploadId; }
    public void setContractTemplateUploadId(String v) { contractTemplateUploadId = v == null ? "" : v; }
    public Map<String, String> getActiveTemplates() { return activeTemplates; }
    public void setActiveTemplates(Map<String, String> activeTemplates) { this.activeTemplates = activeTemplates == null ? new LinkedHashMap<>() : activeTemplates; }
    public Map<String, Map<String, String>> getTemplateMappings() { return templateMappings; }
    public void setTemplateMappings(Map<String, Map<String, String>> templateMappings) { this.templateMappings = templateMappings == null ? new LinkedHashMap<>() : templateMappings; }
    public Map<String, Integer> getPrintCopies() { return printCopies; }
    public void setPrintCopies(Map<String, Integer> printCopies) {
        this.printCopies = printCopies == null ? new LinkedHashMap<>() : new LinkedHashMap<>(printCopies);
        this.printCopies.putIfAbsent("hf", 1); this.printCopies.putIfAbsent("tt", 2); this.printCopies.putIfAbsent("qr", 2);
    }
}
