package az.gmb.taxdata.model;

import java.util.List;
public record OutgoingInvoiceImportResult(String uploadId,int parsed,int added,int skipped,boolean cached,List<OutgoingInvoice> invoices) {}
