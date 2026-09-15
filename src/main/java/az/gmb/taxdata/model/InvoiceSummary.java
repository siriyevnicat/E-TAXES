package az.gmb.taxdata.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvoiceSummary(
        String mtNumber,
        String eInvoiceNumber,
        LocalDate invoiceDate,
        String buyerName,
        String buyerVoen,
        String sellerName,
        String sellerVoen,
        int itemCount,
        BigDecimal subtotal,
        BigDecimal vat,
        BigDecimal total,
        String vatLabel,
        String note,
        String sourceSheet
) {}
