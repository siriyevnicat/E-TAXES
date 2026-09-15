package az.gmb.taxdata.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class OutgoingInvoice {
    private String id; private String invoiceNumber; private LocalDate invoiceDate; private String type="CURRENT";
    private String buyerVoen; private String buyerName; private String sellerVoen; private String sellerName; private String basis; private String note;
    private String objectName; private String objectCode;
    private String status="DRAFT"; private boolean manual=true; private String sourceUploadId;
    private List<OutgoingInvoiceItem> items=new ArrayList<>();
    private BigDecimal subtotal=BigDecimal.ZERO, vat=BigDecimal.ZERO, total=BigDecimal.ZERO;
    private LocalDateTime createdAt=LocalDateTime.now(), updatedAt=LocalDateTime.now();
    public String getId(){return id;} public void setId(String v){id=v;}
    public String getInvoiceNumber(){return invoiceNumber;} public void setInvoiceNumber(String v){invoiceNumber=v;}
    public LocalDate getInvoiceDate(){return invoiceDate;} public void setInvoiceDate(LocalDate v){invoiceDate=v;}
    public String getType(){return type;} public void setType(String v){type=v;}
    public String getBuyerVoen(){return buyerVoen;} public void setBuyerVoen(String v){buyerVoen=v;}
    public String getBuyerName(){return buyerName;} public void setBuyerName(String v){buyerName=v;}
    public String getSellerVoen(){return sellerVoen;} public void setSellerVoen(String v){sellerVoen=v;}
    public String getSellerName(){return sellerName;} public void setSellerName(String v){sellerName=v;}
    public String getBasis(){return basis;} public void setBasis(String v){basis=v;}
    public String getNote(){return note;} public void setNote(String v){note=v;}
    public String getObjectName(){return objectName;} public void setObjectName(String v){objectName=v;}
    public String getObjectCode(){return objectCode;} public void setObjectCode(String v){objectCode=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public boolean isManual(){return manual;} public void setManual(boolean v){manual=v;}
    public String getSourceUploadId(){return sourceUploadId;} public void setSourceUploadId(String v){sourceUploadId=v;}
    public List<OutgoingInvoiceItem> getItems(){return items;} public void setItems(List<OutgoingInvoiceItem> v){items=v==null?new ArrayList<>():v;}
    public BigDecimal getSubtotal(){return subtotal;} public void setSubtotal(BigDecimal v){subtotal=v==null?BigDecimal.ZERO:v;}
    public BigDecimal getVat(){return vat;} public void setVat(BigDecimal v){vat=v==null?BigDecimal.ZERO:v;}
    public BigDecimal getTotal(){return total;} public void setTotal(BigDecimal v){total=v==null?BigDecimal.ZERO:v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
