package az.gmb.taxdata.model;

import java.util.List;

public record InvoiceImportResult(String uploadId, int parsed, int added, int skipped, boolean cached, List<InvoiceSummary> invoices) {}
