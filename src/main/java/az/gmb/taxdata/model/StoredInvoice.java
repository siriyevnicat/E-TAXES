package az.gmb.taxdata.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public class StoredInvoice {
    private String id;
    private String invoiceNumber;
    private String mtNumber;
    private LocalDate invoiceDate;
    private String buyerName;
    private String buyerVoen;
    private String sellerName;
    private String sellerVoen;
    private int itemCount;
    private BigDecimal subtotal = BigDecimal.ZERO;
    private BigDecimal vat = BigDecimal.ZERO;
    private BigDecimal total = BigDecimal.ZERO;
    private String vatLabel = "ƏDV-siz";
    private String note;
    private String sourceSheet;
    private String sourceUploadId;
    private boolean manual;
    private InvoiceData manualData;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public String getMtNumber() { return mtNumber; }
    public void setMtNumber(String mtNumber) { this.mtNumber = mtNumber; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(LocalDate invoiceDate) { this.invoiceDate = invoiceDate; }
    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }
    public String getBuyerVoen() { return buyerVoen; }
    public void setBuyerVoen(String buyerVoen) { this.buyerVoen = buyerVoen; }
    public String getSellerName() { return sellerName; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }
    public String getSellerVoen() { return sellerVoen; }
    public void setSellerVoen(String sellerVoen) { this.sellerVoen = sellerVoen; }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal == null ? BigDecimal.ZERO : subtotal; }
    public BigDecimal getVat() { return vat; }
    public void setVat(BigDecimal vat) { this.vat = vat == null ? BigDecimal.ZERO : vat; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total == null ? BigDecimal.ZERO : total; }
    public String getVatLabel() { return vatLabel; }
    public void setVatLabel(String vatLabel) { this.vatLabel = vatLabel; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getSourceSheet() { return sourceSheet; }
    public void setSourceSheet(String sourceSheet) { this.sourceSheet = sourceSheet; }
    public String getSourceUploadId() { return sourceUploadId; }
    public void setSourceUploadId(String sourceUploadId) { this.sourceUploadId = sourceUploadId; }
    public boolean isManual() { return manual; }
    public void setManual(boolean manual) { this.manual = manual; }
    public InvoiceData getManualData() { return manualData; }
    public void setManualData(InvoiceData manualData) { this.manualData = manualData; }

    public InvoiceSummary toSummary() {
        return new InvoiceSummary(mtNumber, invoiceNumber, invoiceDate, buyerName, buyerVoen, sellerName, sellerVoen, itemCount,
                subtotal, vat, total, vatLabel, note, sourceSheet);
    }

    public static StoredInvoice fromSummary(InvoiceSummary s, String uploadId) {
        StoredInvoice x = new StoredInvoice();
        x.setInvoiceNumber(s.eInvoiceNumber()); x.setMtNumber(s.mtNumber()); x.setInvoiceDate(s.invoiceDate());
        x.setBuyerName(s.buyerName()); x.setBuyerVoen(s.buyerVoen()); x.setSellerName(s.sellerName()); x.setSellerVoen(s.sellerVoen()); x.setItemCount(s.itemCount());
        x.setSubtotal(s.subtotal()); x.setVat(s.vat()); x.setTotal(s.total()); x.setVatLabel(s.vatLabel());
        x.setNote(s.note()); x.setSourceSheet(s.sourceSheet()); x.setSourceUploadId(uploadId); x.setManual(false);
        return x;
    }
}
