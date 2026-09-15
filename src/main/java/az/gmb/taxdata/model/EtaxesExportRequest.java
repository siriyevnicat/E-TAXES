package az.gmb.taxdata.model;

public class EtaxesExportRequest {
    private String phoneNumber;
    private String userId;
    private String tin;
    private String contragentTin;
    private String invoiceType = "received";
    private String startDate;
    private String endDate;
    private int concurrencyLimit = 20;
    private boolean importIntoRegistry = true;
    private String agentGrant;

    public String getPhoneNumber(){return phoneNumber;}
    public void setPhoneNumber(String v){this.phoneNumber=v;}
    public String getUserId(){return userId;}
    public void setUserId(String v){this.userId=v;}
    public String getTin(){return tin;}
    public void setTin(String v){this.tin=v;}
    public String getContragentTin(){return contragentTin;}
    public void setContragentTin(String v){this.contragentTin=v;}
    public String getInvoiceType(){return invoiceType;}
    public void setInvoiceType(String v){this.invoiceType=v;}
    public String getStartDate(){return startDate;}
    public void setStartDate(String v){this.startDate=v;}
    public String getEndDate(){return endDate;}
    public void setEndDate(String v){this.endDate=v;}
    public int getConcurrencyLimit(){return concurrencyLimit;}
    public void setConcurrencyLimit(int v){this.concurrencyLimit=v;}
    public boolean isImportIntoRegistry(){return importIntoRegistry;}
    public void setImportIntoRegistry(boolean v){this.importIntoRegistry=v;}
    public String getAgentGrant(){return agentGrant;}
    public void setAgentGrant(String v){this.agentGrant=v;}
}
