package az.gmb.taxdata.model;

import java.time.LocalDateTime;

public class ContractRecord {
    private String id; private String contractNo; private String contractDate; private String executorCompanyId; private String customerCompanyId;
    private String executorName; private String customerName; private String fileName; private String relativePath; private boolean externalFile;
    private LocalDateTime createdAt=LocalDateTime.now(), updatedAt=LocalDateTime.now();
    public String getId(){return id;} public void setId(String v){id=v;}
    public String getContractNo(){return contractNo;} public void setContractNo(String v){contractNo=v;}
    public String getContractDate(){return contractDate;} public void setContractDate(String v){contractDate=v;}
    public String getExecutorCompanyId(){return executorCompanyId;} public void setExecutorCompanyId(String v){executorCompanyId=v;}
    public String getCustomerCompanyId(){return customerCompanyId;} public void setCustomerCompanyId(String v){customerCompanyId=v;}
    public String getExecutorName(){return executorName;} public void setExecutorName(String v){executorName=v;}
    public String getCustomerName(){return customerName;} public void setCustomerName(String v){customerName=v;}
    public String getFileName(){return fileName;} public void setFileName(String v){fileName=v;}
    public String getRelativePath(){return relativePath;} public void setRelativePath(String v){relativePath=v;}
    public boolean isExternalFile(){return externalFile;} public void setExternalFile(boolean v){externalFile=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
