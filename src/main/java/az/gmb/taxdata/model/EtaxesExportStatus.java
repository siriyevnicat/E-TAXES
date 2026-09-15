package az.gmb.taxdata.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class EtaxesExportStatus {
    private String taskId;
    private String status = "QUEUED";
    private String phase = "Hazırlanır";
    private String message = "";
    private int progress;
    private int invoiceCount;
    private int processedInvoices;
    private int rowCount;
    private int importedCount;
    private int skippedCount;
    private String fileName = "";
    private String downloadUrl = "";
    private boolean downloadReady;
    private boolean importIntoRegistry;
    private Instant startedAt;
    private Instant finishedAt;
    private List<String> logs = new ArrayList<>();

    public String getTaskId(){return taskId;}
    public void setTaskId(String v){this.taskId=v;}
    public String getStatus(){return status;}
    public void setStatus(String v){this.status=v;}
    public String getPhase(){return phase;}
    public void setPhase(String v){this.phase=v;}
    public String getMessage(){return message;}
    public void setMessage(String v){this.message=v;}
    public int getProgress(){return progress;}
    public void setProgress(int v){this.progress=Math.max(0,Math.min(100,v));}
    public int getInvoiceCount(){return invoiceCount;}
    public void setInvoiceCount(int v){this.invoiceCount=v;}
    public int getProcessedInvoices(){return processedInvoices;}
    public void setProcessedInvoices(int v){this.processedInvoices=v;}
    public int getRowCount(){return rowCount;}
    public void setRowCount(int v){this.rowCount=v;}
    public int getImportedCount(){return importedCount;}
    public void setImportedCount(int v){this.importedCount=v;}
    public int getSkippedCount(){return skippedCount;}
    public void setSkippedCount(int v){this.skippedCount=v;}
    public String getFileName(){return fileName;}
    public void setFileName(String v){this.fileName=v;}
    public String getDownloadUrl(){return downloadUrl;}
    public void setDownloadUrl(String v){this.downloadUrl=v;}
    public boolean isDownloadReady(){return downloadReady;}
    public void setDownloadReady(boolean v){this.downloadReady=v;}
    public boolean isImportIntoRegistry(){return importIntoRegistry;}
    public void setImportIntoRegistry(boolean v){this.importIntoRegistry=v;}
    public Instant getStartedAt(){return startedAt;}
    public void setStartedAt(Instant v){this.startedAt=v;}
    public Instant getFinishedAt(){return finishedAt;}
    public void setFinishedAt(Instant v){this.finishedAt=v;}
    public List<String> getLogs(){return logs;}
    public void setLogs(List<String> v){this.logs=v==null?new ArrayList<>():v;}
}
