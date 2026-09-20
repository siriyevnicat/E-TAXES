package az.gmb.taxdata.model;

import java.math.BigDecimal;

public record InvoiceItem(String name, String unit, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {}
