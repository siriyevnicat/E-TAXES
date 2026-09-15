package az.gmb.taxdata.controller;

import az.gmb.taxdata.model.*;
import az.gmb.taxdata.auth.CurrentUserContext;
import az.gmb.taxdata.service.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/api")
public class AppController {
    private final StorageService storage;
    private final CompanyExcelService companyService;
    private final InvoiceExcelService invoiceService;
    private final DocumentGeneratorService generator;
    private final WorkspaceDataService workspaceData;
    private final CompanyRegistryService companyRegistry;
    private final WorkspaceArchiveService workspaceArchive;
    private final TemplateEditorService templateEditor;
    private final PrintPacketService printPacket;
    private final OutgoingInvoiceService outgoingService;
    private final ContractService contractService;
    private final GeneratedDocumentArchiveService generatedArchive;

    public AppController(StorageService storage, CompanyExcelService companyService, InvoiceExcelService invoiceService,
                         DocumentGeneratorService generator, WorkspaceDataService workspaceData, CompanyRegistryService companyRegistry, WorkspaceArchiveService workspaceArchive,
                         TemplateEditorService templateEditor, PrintPacketService printPacket, OutgoingInvoiceService outgoingService, ContractService contractService, GeneratedDocumentArchiveService generatedArchive) {
        this.storage=storage; this.companyService=companyService; this.invoiceService=invoiceService; this.generator=generator;
        this.workspaceData=workspaceData; this.companyRegistry=companyRegistry; this.workspaceArchive=workspaceArchive; this.templateEditor=templateEditor; this.printPacket=printPacket;
        this.outgoingService=outgoingService; this.contractService=contractService; this.generatedArchive=generatedArchive;
    }

    private String ws(String ignored){return storage.normalizeWorkspaceId(CurrentUserContext.require().workspaceId());}

    // ---------------- Workspace ----------------
    @GetMapping("/workspace/state")
    public Map<String,Object> workspaceState(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace) throws IOException {
        String w=ws(workspace); WorkspaceState s=workspaceData.getState(w);
        if(!s.getActiveTemplates().isEmpty()||!s.getTemplateMappings().isEmpty()||!s.getContractTemplateUploadId().isBlank()){
            s.setActiveTemplates(new LinkedHashMap<>()); s.setTemplateMappings(new LinkedHashMap<>()); s.setContractTemplateUploadId(""); workspaceData.saveState(w,s);
        }
        storage.deleteUploadsByPrefix(w,"template-"); storage.deleteUploadsByPrefix(w,"contract-template-");
        Map<String,Object> out=new LinkedHashMap<>(); out.put("companyCount",companyRegistry.count(CurrentUserContext.require().id(),w)); out.put("invoiceCount",s.getInvoices().size());
        out.put("outgoingInvoiceCount",s.getOutgoingInvoices().size()); out.put("contractCount",s.getContracts().size()); out.put("generatedDocumentCount",s.getGeneratedDocuments().size());
        out.put("activeTemplates",Map.of()); out.put("templateMappings",Map.of()); out.put("templateMode","STANDARD_ONLY"); out.put("printCopies",s.getPrintCopies()); return out;
    }

    @PostMapping("/workspace/print-copies")
    public Map<String,Object> savePrintCopies(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                               @RequestBody Map<String,Integer> copies) throws IOException {
        workspaceData.savePrintCopies(ws(workspace),copies); return Map.of("saved",true,"printCopies",workspaceData.printCopies(ws(workspace)));
    }

    @GetMapping("/workspace/export")
    public ResponseEntity<Resource> exportWorkspace(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace) throws IOException {
        byte[] bytes=workspaceArchive.exportWorkspace(ws(workspace));
        return downloadBytes(bytes,"taxdata_workspace_backup.zip",MediaType.parseMediaType("application/zip"));
    }

    @PostMapping(value="/workspace/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> importWorkspace(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                               @RequestPart("file") MultipartFile file) throws IOException {
        if(file==null||file.isEmpty())throw new IllegalArgumentException("Workspace backup ZIP faylı seçilməyib.");
        String n=Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT); if(!n.endsWith(".zip"))throw new IllegalArgumentException("Yalnız .zip workspace backup qəbul edilir.");
        String w=ws(workspace); try(InputStream in=file.getInputStream()){workspaceArchive.importWorkspace(w,in);} WorkspaceState s=workspaceData.getState(w);
        companyRegistry.remigrateLegacyWorkspace(CurrentUserContext.require().id(),w);
        int companyCount=companyRegistry.count(CurrentUserContext.require().id(),w);
        return Map.of("imported",true,"companyCount",companyCount,"invoiceCount",s.getInvoices().size());
    }

    // ---------------- Companies ----------------
    @GetMapping("/companies/defaults")
    public Map<String,Object> defaultCompanies(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace) throws IOException {
        List<CompanyInfo> companies=companyRegistry.list(CurrentUserContext.require().id(),ws(workspace)); return Map.of("count",companies.size(),"companies",companies,"storage","SQL");
    }

    @PostMapping(value="/companies/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompanyImportResult uploadCompanies(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                               @RequestPart("file") MultipartFile file) throws IOException {
        String w=ws(workspace); validateExcel(file); String id=storage.saveUpload(w,file,"companies");
        try {
            return companyRegistry.importCompanies(CurrentUserContext.require().id(),w,companyService.parseUpload(w,id));
        } finally {
            storage.deleteUpload(w,id);
        }
    }

    @PostMapping("/companies/save")
    public CompanyInfo saveCompany(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                   @RequestBody CompanyInfo company) throws IOException { return companyRegistry.save(CurrentUserContext.require().id(),ws(workspace),company); }

    @DeleteMapping("/companies/{id}")
    public Map<String,Object> deleteCompany(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@PathVariable String id) throws IOException {
        companyRegistry.delete(CurrentUserContext.require().id(),ws(workspace),id); return Map.of("deleted",true);
    }

    @GetMapping("/companies/template") public ResponseEntity<Resource> companyTemplate() throws IOException {return xlsx(companyService.buildTemplateWithArchiveCompanies(),"company_data_archive.xlsx");}
    @GetMapping("/companies/template/legal") public ResponseEntity<Resource> legalCompanyTemplate() throws IOException {return xlsx(companyService.buildBlankTemplate(CompanyInfo.LEGAL),"huquqi_sexs_bos_sablon.xlsx");}
    @GetMapping("/companies/template/individual") public ResponseEntity<Resource> individualCompanyTemplate() throws IOException {return xlsx(companyService.buildBlankTemplate(CompanyInfo.INDIVIDUAL),"fiziki_sexs_bos_sablon.xlsx");}

    // ---------------- Invoice registry ----------------
    @GetMapping("/invoices/defaults")
    public Map<String,Object> invoiceDefaults(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace) throws IOException {
        List<InvoiceSummary> list=workspaceData.getInvoices(ws(workspace)); return Map.of("count",list.size(),"invoices",list);
    }

    @GetMapping("/invoices/template") public ResponseEntity<Resource> invoiceTemplate() throws IOException {
        return xlsx(invoiceService.buildBlankInvoiceTemplate(),"qaime_import_sablonu.xlsx");
    }

    @PostMapping(value="/invoices/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public InvoiceImportResult uploadInvoices(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                              @RequestPart("file") MultipartFile file) throws IOException {
        String w=ws(workspace); validateExcel(file); String hash=sha256(file);
        String originalName=Optional.ofNullable(file.getOriginalFilename()).orElse("").trim().toLowerCase(Locale.ROOT);
        // Eyni məzmun fərqli fayl adı ilə ayrıca import sayılır. Yalnız eyni məzmun + eyni fayl adı cache-dən tanınır.
        String importKey=hash+"|"+originalName;
        String cachedUpload=workspaceData.importedUploadForHash(w,importKey);
        if(!cachedUpload.isBlank()) {
            List<InvoiceSummary> cached=workspaceData.cachedInvoiceSummaries(w,importKey);
            // V5.4: köhnə cache-lərdə Qəbul edən/Göndərən ad+VÖEN metadata-sı yox idi.
            // Həmin cache yalnız bir dəfə yeni parserlə yenilənir; sonrakı yükləmələr yenə sürətli cache-dən gəlir.
            if(cached.isEmpty() || workspaceData.invoiceImportCacheVersion(w) < 2){cached=invoiceService.listInvoices(w,cachedUpload,"AUTO",20000);workspaceData.recordInvoiceFileImport(w,importKey,cachedUpload,cached);}
            InvoiceImportResult r=workspaceData.importInvoices(w,cachedUpload,cached);
            return new InvoiceImportResult(cachedUpload,cached.size(),r.added(),r.skipped(),true,r.invoices());
        }
        String id=storage.saveUpload(w,file,"invoices"); List<InvoiceSummary> parsed=invoiceService.listInvoices(w,id,"AUTO",20000);
        InvoiceImportResult r=workspaceData.importInvoices(w,id,parsed); workspaceData.recordInvoiceFileImport(w,importKey,id,parsed);
        return new InvoiceImportResult(id,r.parsed(),r.added(),r.skipped(),false,r.invoices());
    }

    @PostMapping("/invoices/save")
    public StoredInvoice saveInvoice(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                     @RequestBody InvoiceSaveRequest request) throws IOException {
        return workspaceData.saveManualInvoice(ws(workspace),request.originalInvoiceNumber(),request.invoice());
    }

    @DeleteMapping("/invoices")
    public Map<String,Object> deleteInvoice(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                            @RequestParam String invoiceNo) throws IOException {
        workspaceData.deleteInvoice(ws(workspace),invoiceNo); return Map.of("deleted",true);
    }

    @GetMapping("/invoices/{uploadId}/list")
    public List<InvoiceSummary> invoiceList(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                            @PathVariable String uploadId) throws IOException { return invoiceService.listInvoices(ws(workspace),uploadId,"AUTO",20000); }

    @GetMapping("/invoices/{uploadId}/mt")
    public List<String> searchMt(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                 @PathVariable String uploadId,@RequestParam(defaultValue="") String q) throws IOException { return invoiceService.findMtNumbers(ws(workspace),uploadId,q,100); }

    @GetMapping("/invoices/preview")
    public InvoiceData previewStored(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@RequestParam String invoiceNo) throws IOException {
        String w=ws(workspace); StoredInvoice x=workspaceData.getStoredInvoice(w,invoiceNo);
        if(x.isManual()&&x.getManualData()!=null)return x.getManualData();
        InvoiceData d=(x.getMtNumber()!=null&&!x.getMtNumber().isBlank())
                ? invoiceService.parseUpload(w,x.getSourceUploadId(),x.getMtNumber(),"AUTO")
                : invoiceService.parseUploadByInvoiceNumber(w,x.getSourceUploadId(),x.getInvoiceNumber(),"AUTO");
        String parsedNo=d.getEInvoiceNumber()==null?"":d.getEInvoiceNumber(); String parsedMt=d.getMtNumber()==null?"":d.getMtNumber();
        if(x.getInvoiceNumber()!=null&&!x.getInvoiceNumber().isBlank()&&(parsedNo.isBlank()||(!parsedMt.isBlank()&&parsedNo.equalsIgnoreCase(parsedMt))))d.setEInvoiceNumber(x.getInvoiceNumber());
        if(x.getMtNumber()!=null&&!x.getMtNumber().isBlank())d.setMtNumber(x.getMtNumber());
        if(x.getInvoiceDate()!=null)d.setInvoiceDate(x.getInvoiceDate());
        if(x.getBuyerName()!=null&&!x.getBuyerName().isBlank())d.setBuyerName(x.getBuyerName());
        if(x.getBuyerVoen()!=null&&!x.getBuyerVoen().isBlank())d.setBuyerVoen(x.getBuyerVoen());
        if(x.getSellerName()!=null&&!x.getSellerName().isBlank())d.setSellerName(x.getSellerName());
        if(x.getSellerVoen()!=null&&!x.getSellerVoen().isBlank())d.setSellerVoen(x.getSellerVoen());
        if(x.getNote()!=null&&!x.getNote().isBlank())d.setNote(az.gmb.taxdata.util.ExcelUtil.cleanNotePrefix(x.getNote()));
        return d;
    }

    @GetMapping("/invoices/{uploadId}/preview")
    public InvoiceData previewLegacy(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                               @PathVariable String uploadId,@RequestParam(defaultValue="") String invoiceNo,@RequestParam(defaultValue="") String mt) throws IOException {
        if(!invoiceNo.isBlank())return invoiceService.parseUploadByInvoiceNumber(ws(workspace),uploadId,invoiceNo,"AUTO");
        if(!mt.isBlank())return invoiceService.parseUpload(ws(workspace),uploadId,mt,"AUTO"); throw new IllegalArgumentException("Qaimə nömrəsi seçilməyib.");
    }


    // ---------------- Göndərilən qaimələr / eFP ----------------
    @GetMapping("/outgoing")
    public Map<String,Object> outgoingList(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace) throws IOException {
        List<OutgoingInvoice> list=outgoingService.list(ws(workspace));return Map.of("count",list.size(),"invoices",list);
    }
    @GetMapping("/outgoing/template") public ResponseEntity<Resource> outgoingTemplate() throws IOException {return xlsx(outgoingService.template(),"EFP Qaimə Paketləmə Şablonu.xlsx");}
    @PostMapping(value="/outgoing/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public OutgoingInvoiceImportResult outgoingUpload(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@RequestPart("file") MultipartFile file) throws IOException {
        String w=ws(workspace);validateExcel(file);String hash=sha256(file);String originalName=Optional.ofNullable(file.getOriginalFilename()).orElse("").trim().toLowerCase(Locale.ROOT);String importKey=hash+"|"+originalName;
        String cached=workspaceData.importedOutgoingUploadForHash(w,importKey);
        if(!cached.isBlank()){List<OutgoingInvoice> list=outgoingService.list(w);return new OutgoingInvoiceImportResult(cached,0,0,0,true,list);}
        String id=storage.saveUpload(w,file,"outgoing");OutgoingInvoiceImportResult r=outgoingService.importExcel(w,id);workspaceData.recordOutgoingFileHash(w,importKey,id);return r;
    }
    @PostMapping("/outgoing/save") public OutgoingInvoice outgoingSave(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@RequestBody OutgoingInvoice invoice) throws IOException {return outgoingService.save(ws(workspace),invoice);}
    @DeleteMapping("/outgoing/{id}") public Map<String,Object> outgoingDelete(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@PathVariable String id)throws IOException{outgoingService.delete(ws(workspace),id);return Map.of("deleted",true);}
    @GetMapping("/outgoing/{id}") public OutgoingInvoice outgoingGet(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@PathVariable String id)throws IOException{return outgoingService.get(ws(workspace),id);}
    @PostMapping("/outgoing/package") public Map<String,Object> outgoingPackage(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@RequestBody EfpPackageRequest request)throws Exception{return outgoingService.packageInvoices(ws(workspace),request.invoiceIds());}
    @GetMapping("/outgoing/package/{file}") public ResponseEntity<Resource> outgoingPackageDownload(@PathVariable String file,@RequestParam(defaultValue="auth") String workspaceId)throws IOException{
        if(!file.matches("[A-Za-z0-9._-]+"))throw new IllegalArgumentException("Yanlış paket adı.");Path root=storage.getEfpPackagesDir(ws(workspaceId));Path p=root.resolve(file).normalize();if(!p.startsWith(root)||!Files.isRegularFile(p))throw new NoSuchFileException(file);return downloadBytes(Files.readAllBytes(p),file,MediaType.parseMediaType("application/zip"));
    }

    // ---------------- Müqavilə arxivi ----------------
    @GetMapping("/contracts") public Map<String,Object> contracts(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace)throws IOException{List<ContractRecord> list=contractService.list(ws(workspace));return Map.of("count",list.size(),"contracts",list,"folder","BROWSER_LOCAL_FIRST");}
    @PostMapping("/contracts/create") public ContractRecord createContract(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@RequestBody ContractRequest request)throws IOException{return contractService.create(ws(workspace),request);}
    @DeleteMapping("/contracts/{id}") public Map<String,Object> deleteContract(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@PathVariable String id,@RequestParam(defaultValue="true") boolean deleteFile)throws IOException{contractService.delete(ws(workspace),id,deleteFile);return Map.of("deleted",true);}
    @PostMapping(value="/contracts/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ContractRecord importContract(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@RequestPart("file") MultipartFile file)throws IOException{validateDocx(file);return contractService.importWord(ws(workspace),file);}
    @GetMapping("/contracts/{id}/download") public ResponseEntity<Resource> downloadContract(@PathVariable String id,@RequestParam(defaultValue="default") String workspaceId)throws IOException{Path p=contractService.file(ws(workspaceId),id);return downloadBytes(Files.readAllBytes(p),p.getFileName().toString(),MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));}
    @GetMapping("/contracts/{id}/preview") public ResponseEntity<String> previewContract(@PathVariable String id,@RequestParam(defaultValue="default") String workspaceId)throws IOException{return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(contractService.previewHtml(ws(workspaceId),id,false,1));}
    @GetMapping("/contracts/{id}/print") public ResponseEntity<String> printContract(@PathVariable String id,@RequestParam(defaultValue="default") String workspaceId,@RequestParam(defaultValue="2") int copies)throws IOException{return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(contractService.previewHtml(ws(workspaceId),id,true,copies));}
    @PostMapping("/contracts/{id}/open-word") public Map<String,Object> openContractWord(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@PathVariable String id)throws IOException{throw new IllegalStateException("Domen/local-first rejimində server kompüterində Word açılmır. Word faylını brauzerdən endirin və ya seçilmiş lokal qovluğa yazın.");}
    @PostMapping("/contracts/open-folder") public Map<String,Object> openContractsFolder(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace)throws IOException{throw new IllegalStateException("Domen/local-first rejimində server qovluğu açılmır. Brauzerdə “Kompüterdə qovluq seç” funksiyasından istifadə edin.");}
    @GetMapping("/contracts/template") public ResponseEntity<Resource> contractTemplate(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace)throws IOException{return downloadBytes(contractService.activeTemplate(ws(workspace)),"muqavile_aktiv_sablon.docx",MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));}
    @GetMapping("/contracts/template/default") public ResponseEntity<Resource> defaultContractTemplate()throws IOException{return downloadBytes(contractService.defaultTemplate(),"muqavile_faktiki_sablon.docx",MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));}
    @PostMapping(value="/contracts/template",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Map<String,Object> uploadContractTemplate(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@RequestPart("file") MultipartFile file)throws IOException{validateDocx(file);return Map.of("uploadId",contractService.uploadTemplate(ws(workspace),file));}
    @PostMapping("/contracts/template/reset") public Map<String,Object> resetContractTemplate(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace)throws IOException{contractService.resetTemplate(ws(workspace));return Map.of("reset",true);}

    // ---------------- Generated documents archive ----------------
    @GetMapping("/document-archive")
    public Map<String,Object> generatedArchive(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace) throws IOException {
        String w=ws(workspace); List<GenerationArchiveRecord> list=generatedArchive.list(w);
        return Map.of("count",list.size(),"records",list,"folder","BROWSER_LOCAL_FIRST");
    }

    @GetMapping("/document-archive/{id}")
    public Map<String,Object> generatedArchiveRecord(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@PathVariable String id) throws IOException {
        String w=ws(workspace); GenerationArchiveRecord r=generatedArchive.get(w,id);
        return Map.of("record",r,"files",generatedArchive.files(w,id),"folder","TEMPORARY_SESSION");
    }

    @DeleteMapping("/document-archive/{id}")
    public Map<String,Object> deleteGeneratedArchive(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@PathVariable String id,@RequestParam(defaultValue="true") boolean deleteFiles) throws IOException {
        generatedArchive.delete(ws(workspace),id,deleteFiles); return Map.of("deleted",true);
    }

    @PostMapping("/document-archive/{id}/open-folder")
    public Map<String,Object> openGeneratedFolder(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@PathVariable String id) throws IOException {
        throw new IllegalStateException("Domen/local-first rejimində server qovluğu açılmır. ZIP-i istifadəçinin seçdiyi lokal qovluğa yazın.");
    }

    @PostMapping("/document-archive/{id}/open-file")
    public Map<String,Object> openGeneratedFile(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@PathVariable String id,@RequestParam String file) throws IOException {
        throw new IllegalStateException("Domen/local-first rejimində serverdə Excel/Word açılmır. Faylı endirin və ya lokal qovluğa yazın.");
    }

    // ---------------- Templates ----------------
    @GetMapping("/templates/{type}")
    public ResponseEntity<Resource> documentTemplate(@PathVariable String type,@RequestParam(defaultValue="LEGAL") String entityType) throws IOException {
        String t=normalizeTemplateType(type); String normalizedEntity=normalizeEntity(entityType); byte[] bytes=generator.getDefaultTemplate(t,normalizedEntity); String entity=CompanyInfo.INDIVIDUAL.equalsIgnoreCase(normalizedEntity)?"fiziki":"huquqi";
        return xlsx(bytes,templateFileName(t,entity,"standart"));
    }

    @GetMapping("/templates/{type}/active")
    public ResponseEntity<Resource> activeDocumentTemplate(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                                            @PathVariable String type,@RequestParam(defaultValue="LEGAL") String entityType) throws IOException {
        String t=normalizeTemplateType(type),e=normalizeEntity(entityType); byte[] bytes=generator.getActiveTemplate(ws(workspace),t,e);
        return xlsx(bytes,templateFileName(t,e.equals(CompanyInfo.INDIVIDUAL)?"fiziki":"huquqi","aktiv"));
    }

    @PostMapping(value="/templates/upload/{type}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> uploadDocumentTemplate(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                                      @PathVariable String type,@RequestParam(defaultValue="LEGAL") String entityType,@RequestPart("file") MultipartFile file) throws IOException {
        normalizeTemplateType(type); normalizeEntity(entityType);
        throw new IllegalStateException("Vahid faktiki standart şablon rejimi aktivdir. Kompüter və istifadəçi üzrə ayrıca Excel şablonu yüklənmir.");
    }

    @PostMapping("/templates/{type}/reset")
    public Map<String,Object> resetTemplate(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                            @PathVariable String type,@RequestParam(defaultValue="LEGAL") String entityType) throws IOException {
        workspaceData.resetTemplate(ws(workspace),normalizeTemplateType(type),normalizeEntity(entityType)); return Map.of("reset",true);
    }

    @GetMapping("/templates/{type}/editor")
    public Map<String,Object> templateEditorData(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                                 @PathVariable String type,@RequestParam(defaultValue="LEGAL") String entityType,@RequestParam(defaultValue="") String uploadId) throws IOException {
        return templateEditor.editorData(ws(workspace),type,entityType,uploadId);
    }
    @PostMapping("/templates/{type}/mapping")
    public Map<String,Object> saveTemplateMapping(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                                  @PathVariable String type,@RequestParam(defaultValue="LEGAL") String entityType,@RequestBody Map<String,String> mapping) throws IOException {
        throw new IllegalStateException("Standart şablon rejimində dinamik hüceyrə xəritəsi dəyişdirilmir.");
    }
    @PostMapping("/templates/{type}/cell-edits")
    public Map<String,Object> saveTemplateEdits(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,
                                                @PathVariable String type,@RequestParam(defaultValue="LEGAL") String entityType,@RequestParam(defaultValue="") String uploadId,@RequestBody Map<String,String> edits) throws IOException {
        throw new IllegalStateException("Standart şablon rejimində şablonun statik hüceyrələri dəyişdirilmir.");
    }

    // ---------------- Generation / print ----------------
    @PostMapping("/generate")
    public BatchGenerationResult generate(@RequestHeader(value="X-Workspace-Id",defaultValue="default") String workspace,@RequestBody GenerationRequest request) throws IOException {
        GenerationRequest r=new GenerationRequest(ws(workspace),request.invoiceUploadId(),null,request.executorCompanyId(),request.ordererCompanyId(),null,null,request.mtNumber(),request.mtNumbers(),request.invoiceNumbers(),request.documentDate(),request.contractNo(),request.contractDate(),request.startingDocumentNo(),request.invoiceNo(),request.priceProtocolNo(),request.handoverNo(),"AUTO",request.noteOverride(),null,null,null);
        return generator.generate(r);
    }

    @GetMapping("/print/{folder}")
    public ResponseEntity<String> print(@PathVariable String folder,@RequestParam(defaultValue="auth") String workspaceId,@RequestParam(defaultValue="1") int hfCopies,@RequestParam(defaultValue="2") int ttCopies,@RequestParam(defaultValue="2") int qrCopies,@RequestParam(defaultValue="0") int autoprint,@RequestParam(defaultValue="0") int preview) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(printPacket.render(ws(workspaceId),folder,hfCopies,ttCopies,qrCopies,autoprint==1,preview==1));
    }

    @GetMapping("/output/{folder}/{file}")
    public ResponseEntity<Resource> download(@PathVariable String folder,@PathVariable String file,@RequestParam(defaultValue="auth") String workspaceId) throws IOException {
        if(!file.matches("[A-Za-z0-9._-]+"))throw new IllegalArgumentException("Yanlış fayl yolu."); Path root=storage.getOutputsDir(ws(workspaceId)).toAbsolutePath().normalize(); Path p=root.resolve(folder).resolve(file).normalize();
        if(!p.startsWith(root)||!Files.isRegularFile(p))throw new NoSuchFileException(file); return downloadBytes(Files.readAllBytes(p),file,MediaType.APPLICATION_OCTET_STREAM);
    }

    private String templateFileName(String t,String entity,String suffix){return switch(t){case"hf"->"hesab_faktura_"+entity+"_"+suffix+"_sablon.xlsx";case"qr"->"qiymet_razilasma_"+entity+"_"+suffix+"_sablon.xlsx";case"tt"->"tehvil_teslim_"+entity+"_"+suffix+"_sablon.xlsx";default->"sablon.xlsx";};}
    private ResponseEntity<Resource> xlsx(byte[]bytes,String filename){return downloadBytes(bytes,filename,MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));}
    private ResponseEntity<Resource> downloadBytes(byte[]bytes,String filename,MediaType type){String encoded=URLEncoder.encode(filename,StandardCharsets.UTF_8).replace("+","%20");return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+encoded).contentType(type).contentLength(bytes.length).body(new ByteArrayResource(bytes));}
    private String normalizeTemplateType(String type){String t=type==null?"":type.toLowerCase(Locale.ROOT);if(!Set.of("hf","qr","tt").contains(t))throw new IllegalArgumentException("Şablon tipi hf, qr və ya tt olmalıdır.");return t;}
    private String normalizeEntity(String e){return CompanyInfo.INDIVIDUAL.equalsIgnoreCase(e)?CompanyInfo.INDIVIDUAL:CompanyInfo.LEGAL;}
    private void validateExcel(MultipartFile f){if(f==null||f.isEmpty())throw new IllegalArgumentException("Excel faylı seçilməyib.");String n=Optional.ofNullable(f.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);if(!(n.endsWith(".xlsx")||n.endsWith(".xls")))throw new IllegalArgumentException("Yalnız .xlsx və .xls faylları qəbul edilir.");}
    private void validateXlsx(MultipartFile f) throws IOException {
        if(f==null||f.isEmpty())throw new IllegalArgumentException("Şablon faylı seçilməyib.");
        String n=Optional.ofNullable(f.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if(!n.endsWith(".xlsx"))throw new IllegalArgumentException("Sənəd şablonu .xlsx formatında olmalıdır.");
        if(f.getSize()>12L*1024L*1024L)throw new IllegalArgumentException("Şablon faylı 12 MB-dan böyük ola bilməz.");
        try(InputStream in=f.getInputStream(); XSSFWorkbook wb=new XSSFWorkbook(in)){
            if(wb.getNumberOfSheets()<1)throw new IllegalArgumentException("Excel şablonunda ən azı bir iş vərəqi olmalıdır.");
            if(wb.getSheetAt(0).getLastRowNum()>5000)throw new IllegalArgumentException("Şablon həddən artıq böyükdür. İlk vərəq 5000 sətirdən çox ola bilməz.");
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Şablon faylı etibarlı .xlsx Excel faylı deyil.");}
    }
    private void validateDocx(MultipartFile f){if(f==null||f.isEmpty())throw new IllegalArgumentException("Word faylı seçilməyib.");String n=Optional.ofNullable(f.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);if(!n.endsWith(".docx"))throw new IllegalArgumentException("Yalnız .docx faylı qəbul edilir.");}
    private String sha256(MultipartFile f) throws IOException {try{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=f.getInputStream()){byte[]buf=new byte[8192];int n;while((n=in.read(buf))>0)md.update(buf,0,n);}return HexFormat.of().formatHex(md.digest());}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
