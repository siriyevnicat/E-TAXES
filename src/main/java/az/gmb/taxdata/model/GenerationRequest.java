package az.gmb.taxdata.model;

import java.util.List;

public record GenerationRequest(
        String workspaceId,
        String invoiceUploadId,
        String companyUploadId,
        String executorCompanyId,
        String ordererCompanyId,
        CompanyInfo executorCompany,
        CompanyInfo ordererCompany,
        String mtNumber,
        List<String> mtNumbers,
        List<String> invoiceNumbers,
        String documentDate,
        String contractNo,
        String contractDate,
        String startingDocumentNo,
        String invoiceNo,
        String priceProtocolNo,
        String handoverNo,
        String vatMode,
        String noteOverride,
        String hfTemplateUploadId,
        String qrTemplateUploadId,
        String ttTemplateUploadId
) {}
