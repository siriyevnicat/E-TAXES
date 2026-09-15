package az.gmb.taxdata.model;

import java.util.List;

public record GenerationResult(
        String folderName,
        String downloadUrl,
        String mtNumber,
        List<String> files,
        InvoiceData invoice
) {}
