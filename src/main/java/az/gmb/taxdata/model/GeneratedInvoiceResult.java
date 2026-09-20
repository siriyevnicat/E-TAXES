package az.gmb.taxdata.model;

import java.util.List;

public record GeneratedInvoiceResult(
        String mtNumber,
        String eInvoiceNumber,
        String documentNumber,
        List<DocumentFileInfo> documents,
        InvoiceData invoice
) {}
