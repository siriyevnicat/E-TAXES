package az.gmb.taxdata.service;

import az.gmb.taxdata.auth.AuthenticatedUser;
import az.gmb.taxdata.model.EtaxesExportRequest;
import az.gmb.taxdata.model.EtaxesExportStatus;
import org.springframework.stereotype.Service;

/**
 * Production 6.5.0: e-Taxes browser/API işi Railway serverində icra edilmir.
 * Bu servis yalnız köhnə endpoint müqaviləsini təhlükəsiz şəkildə bağlamaq üçün saxlanılır.
 * Faktiki iş taxdata.agent.LocalEtaxesAgentService tərəfindən istifadəçinin kompüterində görülür.
 */
@Service
public class EtaxesExportService {
    private static final String LOCAL_AGENT_MESSAGE = "e-Taxes əməliyyatı TaxData Local Agent vasitəsilə istifadəçinin öz kompüterində aparılır. Railway serverində browser yoxdur.";

    public EtaxesExportStatus start(AuthenticatedUser owner,String workspaceId,EtaxesExportRequest request){
        throw new IllegalStateException(LOCAL_AGENT_MESSAGE);
    }
    public EtaxesExportStatus active(String ownerUserId){return null;}
    public EtaxesExportStatus status(String ownerUserId,String taskId){throw new IllegalArgumentException("Server e-Taxes tapşırığı yoxdur. "+LOCAL_AGENT_MESSAGE);}
    public void cancel(String ownerUserId,String taskId){throw new IllegalArgumentException("Server e-Taxes tapşırığı yoxdur. "+LOCAL_AGENT_MESSAGE);}
}
