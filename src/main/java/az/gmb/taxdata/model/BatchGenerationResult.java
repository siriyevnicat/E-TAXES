package az.gmb.taxdata.model;

import java.util.List;

public record BatchGenerationResult(
        String folderName,
        String downloadUrl,
        String previewUrl,
        String printUrl,
        List<GeneratedInvoiceResult> results,
        String archiveId
) {}
