package az.gmb.taxdata.service;

import az.gmb.taxdata.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;

@Service
public class GeneratedDocumentArchiveService {
    private final StorageService storage;
    private final WorkspaceDataService workspace;
    private final ObjectMapper mapper;

    public GeneratedDocumentArchiveService(StorageService storage, WorkspaceDataService workspace, ObjectMapper mapper) {
        this.storage=storage; this.workspace=workspace; this.mapper=mapper;
    }

    public synchronized GenerationArchiveRecord register(String ws, Path folder, BatchManifest manifest, GenerationRequest req) throws IOException {
        WorkspaceState state=workspace.getState(ws);
        String folderName=folder.getFileName().toString();
        GenerationArchiveRecord rec=state.getGeneratedDocuments().stream().filter(x->folderName.equals(x.getFolderName())).findFirst().orElse(null);
        if(rec==null){rec=new GenerationArchiveRecord();rec.setId("GEN_"+UUID.randomUUID());rec.setCreatedAt(java.time.LocalDateTime.now());state.getGeneratedDocuments().add(rec);}
        rec.setUpdatedAt(java.time.LocalDateTime.now()); rec.setFolderName(folderName); rec.setExternalFolder(false);
        rec.setRelativePath(storage.getWorkspaceDir(ws).relativize(folder).toString().replace('\\','/'));
        rec.setZipFileName("alt_senedler.zip"); rec.setStartingDocumentNo(manifest.startingDocumentNo());
        rec.setDocumentNumbers(manifest.documentNumbers()); rec.setContractNumbers(manifest.contractNumbers()); rec.setContractDates(manifest.contractDates());
        List<String> invoiceNos=new ArrayList<>(); LinkedHashMap<String,String> buyers=new LinkedHashMap<>(), sellers=new LinkedHashMap<>();
        for(InvoiceData inv:manifest.invoices()){
            String key=key(inv); if(!key.isBlank())invoiceNos.add(key);
            CompanyInfo buyer=party(manifest.orderers(),key,manifest.orderer()); CompanyInfo seller=party(manifest.executors(),key,manifest.executor());
            buyers.put(key,buyer==null?safe(inv.getBuyerName()):safe(buyer.getCompany())); sellers.put(key,seller==null?safe(inv.getSellerName()):safe(seller.getCompany()));
        }
        rec.setInvoiceNumbers(invoiceNos);rec.setBuyerNames(buyers);rec.setSellerNames(sellers);
        if(req!=null){rec.setExecutorCompanyId(req.executorCompanyId());rec.setOrdererCompanyId(req.ordererCompanyId());rec.setDocumentDate(req.documentDate());rec.setContractNo(req.contractNo());rec.setContractDate(req.contractDate());rec.setNoteOverride(req.noteOverride());}
        workspace.saveState(ws,state);return rec;
    }

    public List<GenerationArchiveRecord> list(String ws) throws IOException {
        WorkspaceState state=workspace.getState(ws); Map<String,GenerationArchiveRecord> byFolder=new LinkedHashMap<>();
        for(GenerationArchiveRecord r:state.getGeneratedDocuments()) if(!blank(r.getFolderName()))byFolder.put(r.getFolderName().toLowerCase(Locale.ROOT),r);
        Path root=storage.getOutputsDir(ws);
        try(var stream=Files.list(root)){
            for(Path dir:stream.filter(Files::isDirectory).toList()){
                Path mf=dir.resolve("manifest.json"); if(!Files.isRegularFile(mf))continue;
                String folder=dir.getFileName().toString(), k=folder.toLowerCase(Locale.ROOT); if(byFolder.containsKey(k))continue;
                try{BatchManifest m=mapper.readValue(mf.toFile(),BatchManifest.class);GenerationArchiveRecord r=fromManifest(ws,dir,m);byFolder.put(k,r);}catch(Exception ignored){}
            }
        }
        return byFolder.values().stream().sorted(Comparator.comparing(GenerationArchiveRecord::getCreatedAt,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(GenerationArchiveRecord::getFolderName,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
    }

    public GenerationArchiveRecord get(String ws,String id) throws IOException {return list(ws).stream().filter(x->Objects.equals(x.getId(),id)).findFirst().orElseThrow(()->new IllegalArgumentException("Alt sənəd arxiv qeydi tapılmadı."));}
    public Path folder(String ws,String id)throws IOException{GenerationArchiveRecord r=get(ws,id);Path root=storage.getOutputsDir(ws),p=root.resolve(r.getFolderName()).normalize();if(!p.startsWith(root)||!Files.isDirectory(p))throw new NoSuchFileException(r.getFolderName());return p;}
    public List<String> files(String ws,String id)throws IOException{Path p=folder(ws,id);try(var s=Files.list(p)){return s.filter(Files::isRegularFile).map(x->x.getFileName().toString()).filter(x->!x.equals("manifest.json")).sorted(String.CASE_INSENSITIVE_ORDER).toList();}}
    public synchronized void delete(String ws,String id,boolean deleteFiles)throws IOException{WorkspaceState state=workspace.getState(ws);GenerationArchiveRecord r=state.getGeneratedDocuments().stream().filter(x->Objects.equals(x.getId(),id)).findFirst().orElse(null);if(r==null){r=get(ws,id);if(deleteFiles)deleteRecursive(folder(ws,id));return;}if(deleteFiles){Path root=storage.getOutputsDir(ws),p=root.resolve(r.getFolderName()).normalize();if(p.startsWith(root))deleteRecursive(p);}state.getGeneratedDocuments().remove(r);workspace.saveState(ws,state);}

    private GenerationArchiveRecord fromManifest(String ws,Path dir,BatchManifest m)throws IOException{GenerationArchiveRecord r=new GenerationArchiveRecord();r.setId("OUT_"+Integer.toHexString(dir.getFileName().toString().toLowerCase(Locale.ROOT).hashCode()));r.setFolderName(dir.getFileName().toString());r.setExternalFolder(true);r.setRelativePath(storage.getWorkspaceDir(ws).relativize(dir).toString().replace('\\','/'));r.setStartingDocumentNo(m.startingDocumentNo());r.setDocumentNumbers(m.documentNumbers());r.setContractNumbers(m.contractNumbers());r.setContractDates(m.contractDates());List<String> nos=new ArrayList<>();LinkedHashMap<String,String>b=new LinkedHashMap<>(),s=new LinkedHashMap<>();for(InvoiceData inv:m.invoices()){String key=key(inv);if(!key.isBlank())nos.add(key);CompanyInfo bb=party(m.orderers(),key,m.orderer()),ss=party(m.executors(),key,m.executor());b.put(key,bb==null?safe(inv.getBuyerName()):safe(bb.getCompany()));s.put(key,ss==null?safe(inv.getSellerName()):safe(ss.getCompany()));}r.setInvoiceNumbers(nos);r.setBuyerNames(b);r.setSellerNames(s);try{BasicFileAttributes a=Files.readAttributes(dir,BasicFileAttributes.class);r.setCreatedAt(LocalDateTime.ofInstant(a.creationTime().toInstant(),ZoneId.systemDefault()));r.setUpdatedAt(LocalDateTime.ofInstant(a.lastModifiedTime().toInstant(),ZoneId.systemDefault()));}catch(Exception ignored){}return r;}
    private CompanyInfo party(Map<String,CompanyInfo> map,String key,CompanyInfo fallback){return map!=null&&map.get(key)!=null?map.get(key):fallback;}
    private String key(InvoiceData inv){return !blank(inv.getEInvoiceNumber())?inv.getEInvoiceNumber():safe(inv.getMtNumber());}
    private void deleteRecursive(Path p)throws IOException{if(p==null||!Files.exists(p))return;try(var s=Files.walk(p)){for(Path x:s.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(x);}}
    private boolean blank(String x){return x==null||x.isBlank();}private String safe(String x){return x==null?"":x;}
}
