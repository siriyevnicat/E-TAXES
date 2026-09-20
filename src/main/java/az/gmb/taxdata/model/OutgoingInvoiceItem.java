package az.gmb.taxdata.model;

import java.math.BigDecimal;

/** One QAIME_1 row. Field names intentionally mirror the official c1..c17 columns. */
public class OutgoingInvoiceItem {
    private String code;              // c1 Mal kodu
    private String name;              // c2 Mal adı
    private String unit = "Ədəd";     // c3 Ölçü vahidi
    private BigDecimal quantity = BigDecimal.ONE; // c4
    private BigDecimal unitPrice = BigDecimal.ZERO; // c5
    private BigDecimal amount = BigDecimal.ZERO; // c6 Cəmi qiyməti
    private BigDecimal exciseRate = BigDecimal.ZERO; // c7
    private BigDecimal exciseAmount = BigDecimal.ZERO; // c8
    private BigDecimal totalAmount = BigDecimal.ZERO; // c9
    private BigDecimal vatTaxableAmount = BigDecimal.ZERO; // c10
    private BigDecimal vatNonTaxableAmount = BigDecimal.ZERO; // c11
    private BigDecimal vatExemptAmount = BigDecimal.ZERO; // c12
    private BigDecimal vatZeroAmount = BigDecimal.ZERO; // c13
    private BigDecimal vatAmount = BigDecimal.ZERO; // c14
    private BigDecimal roadTaxAmount = BigDecimal.ZERO; // c15
    private BigDecimal finalAmount = BigDecimal.ZERO; // c16
    private String gtin = "0";        // c17 Bar kod
    // Convenience field for manual entry; QAIME_1 itself stores the VAT amount, not a rate column.
    private BigDecimal vatRate = new BigDecimal("18");
    // True when c7..c17 values came explicitly from the official Excel structure.
    private boolean officialFields;

    public String getCode(){return code;} public void setCode(String v){code=v;}
    public String getGtin(){return gtin;} public void setGtin(String v){gtin=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getUnit(){return unit;} public void setUnit(String v){unit=v;}
    public BigDecimal getQuantity(){return quantity;} public void setQuantity(BigDecimal v){quantity=nz(v,BigDecimal.ONE);}
    public BigDecimal getUnitPrice(){return unitPrice;} public void setUnitPrice(BigDecimal v){unitPrice=nz(v);}
    public BigDecimal getAmount(){return amount;} public void setAmount(BigDecimal v){amount=nz(v);}
    public BigDecimal getExciseRate(){return exciseRate;} public void setExciseRate(BigDecimal v){exciseRate=nz(v);}
    public BigDecimal getExciseAmount(){return exciseAmount;} public void setExciseAmount(BigDecimal v){exciseAmount=nz(v);}
    public BigDecimal getTotalAmount(){return totalAmount;} public void setTotalAmount(BigDecimal v){totalAmount=nz(v);}
    public BigDecimal getVatTaxableAmount(){return vatTaxableAmount;} public void setVatTaxableAmount(BigDecimal v){vatTaxableAmount=nz(v);}
    public BigDecimal getVatNonTaxableAmount(){return vatNonTaxableAmount;} public void setVatNonTaxableAmount(BigDecimal v){vatNonTaxableAmount=nz(v);}
    public BigDecimal getVatExemptAmount(){return vatExemptAmount;} public void setVatExemptAmount(BigDecimal v){vatExemptAmount=nz(v);}
    public BigDecimal getVatZeroAmount(){return vatZeroAmount;} public void setVatZeroAmount(BigDecimal v){vatZeroAmount=nz(v);}
    public BigDecimal getVatRate(){return vatRate;} public void setVatRate(BigDecimal v){vatRate=nz(v);}
    public BigDecimal getVatAmount(){return vatAmount;} public void setVatAmount(BigDecimal v){vatAmount=nz(v);}
    public BigDecimal getRoadTaxAmount(){return roadTaxAmount;} public void setRoadTaxAmount(BigDecimal v){roadTaxAmount=nz(v);}
    public BigDecimal getFinalAmount(){return finalAmount;} public void setFinalAmount(BigDecimal v){finalAmount=nz(v);}
    public boolean isOfficialFields(){return officialFields;} public void setOfficialFields(boolean v){officialFields=v;}
    private static BigDecimal nz(BigDecimal v){return v==null?BigDecimal.ZERO:v;}
    private static BigDecimal nz(BigDecimal v,BigDecimal d){return v==null?d:v;}
}
