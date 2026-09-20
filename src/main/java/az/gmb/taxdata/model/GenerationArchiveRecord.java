package az.gmb.taxdata.model;

import java.time.LocalDateTime;
import java.util.*;

public class GenerationArchiveRecord {
    private String id;
    private String folderName;
    private String relativePath;
    private String zipFileName = "alt_senedler.zip";
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<String> invoiceNumbers = new ArrayList<>();
    private Map<String,String> documentNumbers = new LinkedHashMap<>();
    private Map<String,String> buyerNames = new LinkedHashMap<>();
    private Map<String,String> sellerNames = new LinkedHashMap<>();
    private Map<String,String> contractNumbers = new LinkedHashMap<>();
    private Map<String,String> contractDates = new LinkedHashMap<>();
    private String startingDocumentNo = "1";
    private String executorCompanyId = "";
    private String ordererCompanyId = "";
    private String documentDate = "";
    private String contractNo = "";
    private String contractDate = "";
    private String noteOverride = "";
    private boolean externalFolder;

    public String getId(){return id;} public void setId(String v){id=v;}
    public String getFolderName(){return folderName;} public void setFolderName(String v){folderName=v;}
    public String getRelativePath(){return relativePath;} public void setRelativePath(String v){relativePath=v;}
    public String getZipFileName(){return zipFileName;} public void setZipFileName(String v){zipFileName=v==null?"alt_senedler.zip":v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
    public List<String> getInvoiceNumbers(){return invoiceNumbers;} public void setInvoiceNumbers(List<String> v){invoiceNumbers=v==null?new ArrayList<>():new ArrayList<>(v);}
    public Map<String,String> getDocumentNumbers(){return documentNumbers;} public void setDocumentNumbers(Map<String,String> v){documentNumbers=v==null?new LinkedHashMap<>():new LinkedHashMap<>(v);}
    public Map<String,String> getBuyerNames(){return buyerNames;} public void setBuyerNames(Map<String,String> v){buyerNames=v==null?new LinkedHashMap<>():new LinkedHashMap<>(v);}
    public Map<String,String> getSellerNames(){return sellerNames;} public void setSellerNames(Map<String,String> v){sellerNames=v==null?new LinkedHashMap<>():new LinkedHashMap<>(v);}
    public Map<String,String> getContractNumbers(){return contractNumbers;} public void setContractNumbers(Map<String,String> v){contractNumbers=v==null?new LinkedHashMap<>():new LinkedHashMap<>(v);}
    public Map<String,String> getContractDates(){return contractDates;} public void setContractDates(Map<String,String> v){contractDates=v==null?new LinkedHashMap<>():new LinkedHashMap<>(v);}
    public String getStartingDocumentNo(){return startingDocumentNo;} public void setStartingDocumentNo(String v){startingDocumentNo=v==null?"1":v;}
    public String getExecutorCompanyId(){return executorCompanyId;} public void setExecutorCompanyId(String v){executorCompanyId=v==null?"":v;}
    public String getOrdererCompanyId(){return ordererCompanyId;} public void setOrdererCompanyId(String v){ordererCompanyId=v==null?"":v;}
    public String getDocumentDate(){return documentDate;} public void setDocumentDate(String v){documentDate=v==null?"":v;}
    public String getContractNo(){return contractNo;} public void setContractNo(String v){contractNo=v==null?"":v;}
    public String getContractDate(){return contractDate;} public void setContractDate(String v){contractDate=v==null?"":v;}
    public String getNoteOverride(){return noteOverride;} public void setNoteOverride(String v){noteOverride=v==null?"":v;}
    public boolean isExternalFolder(){return externalFolder;} public void setExternalFolder(boolean v){externalFolder=v;}
}
