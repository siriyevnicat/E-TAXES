package az.gmb.taxdata.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class InvoiceData {
    private String mtNumber;
    private String eInvoiceNumber;
    private String note;
    private LocalDate invoiceDate;
    private String buyerName;
    private String buyerVoen;
    private String sellerName;
    private String sellerVoen;
    private final List<InvoiceItem> items = new ArrayList<>();
    private BigDecimal subtotal = BigDecimal.ZERO;
    private BigDecimal vat = BigDecimal.ZERO;
    private BigDecimal total = BigDecimal.ZERO;
    private String vatMode = "AUTO";
    private String vatLabel = "+18% (ƏDV)";
    private String sourceSheet;
    private String parserNote;

    public String getMtNumber() { return mtNumber; }
    public void setMtNumber(String mtNumber) { this.mtNumber = mtNumber; }
    public String getEInvoiceNumber() { return eInvoiceNumber; }
    public void setEInvoiceNumber(String eInvoiceNumber) { this.eInvoiceNumber = eInvoiceNumber; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
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
    public List<InvoiceItem> getItems() { return items; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getVat() { return vat; }
    public void setVat(BigDecimal vat) { this.vat = vat; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public String getVatMode() { return vatMode; }
    public void setVatMode(String vatMode) { this.vatMode = vatMode; }
    public String getVatLabel() { return vatLabel; }
    public void setVatLabel(String vatLabel) { this.vatLabel = vatLabel; }
    public String getSourceSheet() { return sourceSheet; }
    public void setSourceSheet(String sourceSheet) { this.sourceSheet = sourceSheet; }
    public String getParserNote() { return parserNote; }
    public void setParserNote(String parserNote) { this.parserNote = parserNote; }
}
