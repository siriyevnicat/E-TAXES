package az.gmb.taxdata.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class CompanyInfo {
    public static final String LEGAL = "LEGAL";
    public static final String INDIVIDUAL = "INDIVIDUAL";

    private String id;
    private String role;
    private String entityType;
    private String company;
    private String director;
    private String voen;
    private String address;
    private String bankName;
    private String bankCode;
    private String bankSwift;
    private String bankVoen;
    private String bankAccount;
    private String correspondentAccount;
    private String handoverNo;
    private String invoiceNo;
    private String priceProtocolNo;
    private String contractNo;
    private String contractDate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getEntityType() { return normalizeEntityType(entityType, company, director, voen); }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getDirector() { return director; }
    public void setDirector(String director) { this.director = director; }
    public String getVoen() { return voen; }
    public void setVoen(String voen) { this.voen = voen; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public String getBankCode() { return bankCode; }
    public void setBankCode(String bankCode) { this.bankCode = bankCode; }
    public String getBankSwift() { return bankSwift; }
    public void setBankSwift(String bankSwift) { this.bankSwift = bankSwift; }
    public String getBankVoen() { return bankVoen; }
    public void setBankVoen(String bankVoen) { this.bankVoen = bankVoen; }
    public String getBankAccount() { return bankAccount; }
    public void setBankAccount(String bankAccount) { this.bankAccount = bankAccount; }
    public String getCorrespondentAccount() { return correspondentAccount; }
    public void setCorrespondentAccount(String correspondentAccount) { this.correspondentAccount = correspondentAccount; }
    public String getHandoverNo() { return handoverNo; }
    public void setHandoverNo(String handoverNo) { this.handoverNo = handoverNo; }
    public String getInvoiceNo() { return invoiceNo; }
    public void setInvoiceNo(String invoiceNo) { this.invoiceNo = invoiceNo; }
    public String getPriceProtocolNo() { return priceProtocolNo; }
    public void setPriceProtocolNo(String priceProtocolNo) { this.priceProtocolNo = priceProtocolNo; }
    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
    public String getContractDate() { return contractDate; }
    public void setContractDate(String contractDate) { this.contractDate = contractDate; }

    public boolean isIndividual() {
        return INDIVIDUAL.equals(getEntityType());
    }

    public String signerName() {
        if (!isIndividual()) return director == null ? "" : director.trim();
        String s = company == null ? "" : company.trim();
        return s.replaceFirst("(?i)^F\\s*/\\s*Ş\\s*:?\\s*", "")
                .replaceFirst("(?i)^Fiziki\\s+şəxs\\s*:?\\s*", "")
                .trim();
    }

    public String signerLabel() {
        return isIndividual() ? "Fiziki şəxs" : "Direktor";
    }

    public String sealSignatureLabel() {
        return isIndividual() ? "İmza" : "M.Y. İmza";
    }

    public static String normalizeEntityType(String raw, String company, String director) {
        return normalizeEntityType(raw, company, director, null);
    }

    /**
     * Azərbaycan VÖEN məntiqi üzrə bu layihədə istifadəçinin tələb etdiyi qayda:
     * son rəqəm 2 -> fiziki şəxs, son rəqəm 1 -> hüquqi şəxs.
     * VÖEN mövcuddursa bu qayda əl ilə seçilmiş tipdən üstün tutulur.
     */
    public static String normalizeEntityType(String raw, String company, String director, String voen) {
        String digits = voen == null ? "" : voen.replaceAll("\\D", "");
        if (digits.endsWith("2")) return INDIVIDUAL;
        if (digits.endsWith("1")) return LEGAL;

        String x = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (x.contains("FİZİK") || x.contains("FIZIK") || x.equals("FS") || x.equals("F/Ş") || x.equals("INDIVIDUAL")) return INDIVIDUAL;
        if (x.contains("HÜQUQ") || x.contains("HUQUQ") || x.equals("LEGAL")) return LEGAL;

        String c = company == null ? "" : company.trim().toUpperCase(Locale.ROOT);
        if (c.startsWith("F/Ş") || c.startsWith("F / Ş") || c.startsWith("FİZİKİ ŞƏXS") || c.startsWith("FIZIKI SEXS")) return INDIVIDUAL;
        return LEGAL;
    }

    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Şəxs tipi", isIndividual() ? "Fiziki şəxs" : "Hüquqi şəxs");
        m.put("Rol", role);
        m.put("Şirkət / Ad Soyad Ata adı", company);
        m.put("Direktor / Səlahiyyətli şəxs", isIndividual() ? "" : director);
        m.put("VÖEN", voen);
        m.put("Ünvan", address);
        m.put("Bank Adı", bankName);
        m.put("Bank kodu", bankCode);
        m.put("Bank SWIFT", bankSwift);
        m.put("Bank VÖEN", bankVoen);
        m.put("Bank H/h", bankAccount);
        m.put("Bank M/h", correspondentAccount);
        m.put("Təhvil təslim sənəd no", handoverNo);
        m.put("Hesab faktura sənəd no", invoiceNo);
        m.put("Qiymət razılaşdırma sənəd no", priceProtocolNo);
        m.put("Müqavilə №", contractNo);
        m.put("Müqavilə Tarixi", contractDate);
        return m;
    }
}
