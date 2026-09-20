package az.gmb.taxdata.model;

import java.util.List;

public record CompanyImportResult(int parsed, int added, int skipped, List<CompanyInfo> companies) {}
