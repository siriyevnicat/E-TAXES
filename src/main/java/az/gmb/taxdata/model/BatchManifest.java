package az.gmb.taxdata.model;

import java.util.List;
import java.util.Map;

public record BatchManifest(
        String folderName,
        CompanyInfo executor,
        CompanyInfo orderer,
        String contractNo,
        String contractDate,
        String startingDocumentNo,
        Map<String,String> documentNumbers,
        Map<String,CompanyInfo> executors,
        Map<String,CompanyInfo> orderers,
        Map<String,String> contractNumbers,
        Map<String,String> contractDates,
        List<InvoiceData> invoices
) {}
