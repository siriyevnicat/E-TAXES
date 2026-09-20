package az.gmb.taxdata.service;

import az.gmb.taxdata.model.*;
import az.gmb.taxdata.auth.CurrentUserContext;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class ContractService {
    private final StorageService storage; private final WorkspaceDataService workspace; private final CompanyRegistryService companies;
    public ContractService(StorageService storage,WorkspaceDataService workspace,CompanyRegistryService companies){this.storage=storage;this.workspace=workspace;this.companies=companies;}

    public byte[] defaultTemplate() throws IOException {try(InputStream in=new ClassPathResource("contract-templates/muqavile_faktiki.docx").getInputStream()){return in.readAllBytes();}}
    public byte[] activeTemplate(String ws) throws IOException {return defaultTemplate();}
    public synchronized String uploadTemplate(String ws,MultipartFile file)throws IOException{throw new IllegalStateException("Müqavilə üçün vahid faktiki standart şablon aktivdir; kompüter üzrə ayrıca şablon yüklənmir.");}
    public synchronized void resetTemplate(String ws)throws IOException{WorkspaceState st=workspace.getState(ws);st.setContractTemplateUploadId("");workspace.saveState(ws,st);}

    public synchronized ContractRecord create(String ws,ContractRequest req)throws IOException{
        String userId=CurrentUserContext.require().id();CompanyInfo ex=companies.get(userId,ws,req.executorCompanyId());CompanyInfo customer=companies.get(userId,ws,req.customerCompanyId());WorkspaceState st=workspace.getState(ws);
        String date=first(req.contractDate(),customer.getContractDate(),LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        String no=first(req.contractNo(),customer.getContractNo(),"M/"+(st.getContracts().size()+1)+"/"+LocalDate.now().getYear());
        ContractRecord rec=null;if(!blank(req.id()))rec=st.getContracts().stream().filter(x->Objects.equals(x.getId(),req.id())).findFirst().orElse(null);
        if(rec==null){rec=new ContractRecord();rec.setId("CTR_"+UUID.randomUUID());rec.setCreatedAt(LocalDateTime.now());st.getContracts().add(rec);}
        rec.setContractNo(no);rec.setContractDate(date);rec.setExecutorCompanyId(ex.getId());rec.setCustomerCompanyId(customer.getId());rec.setExecutorName(ex.getCompany());rec.setCustomerName(customer.getCompany());rec.setUpdatedAt(LocalDateTime.now());rec.setExternalFile(false);
        String fileName=safeBase(no)+"_"+safeBase(customer.getCompany())+".docx";Path file=storage.getContractsDir(ws).resolve(fileName);generate(activeTemplate(ws),file,ex,customer,no,date);rec.setFileName(fileName);rec.setRelativePath(storage.getWorkspaceDir(ws).relativize(file).toString().replace('\\','/'));
        customer.setContractNo(no);customer.setContractDate(date); // şirkət profilində son müqavilə avtomatik yadda qalır
        workspace.saveState(ws,st);companies.save(userId,ws,customer);return rec;
    }

    public synchronized void delete(String ws,String id,boolean deleteFile)throws IOException{WorkspaceState st=workspace.getState(ws);ContractRecord r=st.getContracts().stream().filter(x->Objects.equals(x.getId(),id)).findFirst().orElse(null);if(r==null){r=list(ws).stream().filter(x->Objects.equals(x.getId(),id)).findFirst().orElseThrow(()->new IllegalArgumentException("Müqavilə tapılmadı."));if(deleteFile&&!blank(r.getFileName()))Files.deleteIfExists(storage.getContractsDir(ws).resolve(r.getFileName()));return;}if(deleteFile&&!blank(r.getFileName()))Files.deleteIfExists(storage.getContractsDir(ws).resolve(r.getFileName()));st.getContracts().remove(r);workspace.saveState(ws,st);}

    public synchronized ContractRecord importWord(String ws,MultipartFile file)throws IOException{String original=Optional.ofNullable(file.getOriginalFilename()).orElse("muqavile.docx");if(!original.toLowerCase(Locale.ROOT).endsWith(".docx"))throw new IllegalArgumentException("Yalnız .docx müqavilə faylı qəbul edilir.");Path target=unique(storage.getContractsDir(ws),safeKeepExt(original));try(InputStream in=file.getInputStream()){Files.copy(in,target);}ContractRecord r=new ContractRecord();r.setId("CTR_"+UUID.randomUUID());r.setFileName(target.getFileName().toString());r.setRelativePath(storage.getWorkspaceDir(ws).relativize(target).toString().replace('\\','/'));r.setExternalFile(true);r.setCustomerName(titleFromFile(target.getFileName().toString()));WorkspaceState st=workspace.getState(ws);st.getContracts().add(r);workspace.saveState(ws,st);return r;}

    public List<ContractRecord> list(String ws)throws IOException{
        WorkspaceState st=workspace.getState(ws);Map<String,ContractRecord> byFile=new LinkedHashMap<>();for(ContractRecord r:st.getContracts())if(!blank(r.getFileName()))byFile.put(r.getFileName().toLowerCase(Locale.ROOT),r);
        try(var files=Files.list(storage.getContractsDir(ws))){for(Path p:files.filter(Files::isRegularFile).filter(x->x.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".docx")).toList()){String k=p.getFileName().toString().toLowerCase(Locale.ROOT);if(!byFile.containsKey(k)){ContractRecord r=new ContractRecord();r.setId("FILE_"+Integer.toHexString(k.hashCode()));r.setFileName(p.getFileName().toString());r.setRelativePath(storage.getWorkspaceDir(ws).relativize(p).toString().replace('\\','/'));r.setExternalFile(true);r.setCustomerName(titleFromFile(r.getFileName()));byFile.put(k,r);}}}
        return byFile.values().stream().sorted(Comparator.comparing(ContractRecord::getUpdatedAt,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(ContractRecord::getFileName,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
    }

    public Path file(String ws,String id)throws IOException{ContractRecord r=list(ws).stream().filter(x->Objects.equals(x.getId(),id)).findFirst().orElseThrow(()->new IllegalArgumentException("Müqavilə tapılmadı."));Path p=storage.getContractsDir(ws).resolve(r.getFileName()).normalize();if(!p.startsWith(storage.getContractsDir(ws))||!Files.isRegularFile(p))throw new NoSuchFileException(r.getFileName());return p;}

    public String previewHtml(String ws,String id,boolean print,int copies)throws IOException{
        Path p=file(ws,id);StringBuilder body=new StringBuilder();try(InputStream in=Files.newInputStream(p);XWPFDocument d=new XWPFDocument(in)){for(IBodyElement e:d.getBodyElements()){if(e instanceof XWPFParagraph par){String t=esc(par.getText());if(!t.isBlank())body.append("<p>").append(t).append("</p>");}else if(e instanceof XWPFTable t){body.append("<table>");for(XWPFTableRow row:t.getRows()){body.append("<tr>");for(XWPFTableCell c:row.getTableCells())body.append("<td>").append(esc(c.getText())).append("</td>");body.append("</tr>");}body.append("</table>");}}}
        String doc="<section class='doc'>"+body+"</section>";StringBuilder all=new StringBuilder();for(int i=0;i<Math.max(1,Math.min(copies,10));i++)all.append(doc);
        return "<!doctype html><html><head><meta charset='utf-8'><title>Müqavilə</title><style>@page{size:A4;margin:15mm}body{font-family:Arial,sans-serif;color:#111}.doc{max-width:190mm;margin:0 auto 12mm}.doc:not(:last-child){page-break-after:always}p{font-size:12px;line-height:1.35;text-align:justify}table{border-collapse:collapse;width:100%;margin:8px 0;font-size:11px}td{border:1px solid #999;padding:5px;vertical-align:top}@media print{button{display:none}}</style></head><body>"+(print?"<button onclick='window.print()'>Çap et</button>":"")+all+(print?"<script>setTimeout(()=>window.print(),300)</script>":"")+"</body></html>";
    }

    private void generate(byte[] template,Path target,CompanyInfo ex,CompanyInfo cu,String no,String date)throws IOException{
        LinkedHashMap<String,String> m=replacements(ex,cu,no,date);
        try(InputStream in=new ByteArrayInputStream(template);XWPFDocument doc=new XWPFDocument(in)){
            for(XWPFParagraph p:doc.getParagraphs())replace(p,m);
            for(XWPFTable t:doc.getTables())replaceTable(t,m);
            try(OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)){doc.write(out);}
        }
        // Textbox/shape kimi XWPF body siyahısına düşməyən Word XML hissələrində qalan
        // placeholder-ları da əvəz et. Beləliklə production sənədində {{...}} qalmır.
        replaceRemainingDocxPlaceholders(target,m);
    }

    private void replaceRemainingDocxPlaceholders(Path docx,Map<String,String> replacements)throws IOException{
        Path tmp=Files.createTempFile(docx.getParent(),"contract_clean_",".docx");
        try(ZipInputStream zin=new ZipInputStream(Files.newInputStream(docx));ZipOutputStream zout=new ZipOutputStream(Files.newOutputStream(tmp,StandardOpenOption.TRUNCATE_EXISTING))){
            ZipEntry e;
            while((e=zin.getNextEntry())!=null){
                ZipEntry outEntry=new ZipEntry(e.getName());
                zout.putNextEntry(outEntry);
                byte[] data=zin.readAllBytes();
                if(e.getName().endsWith(".xml")){
                    String xml=new String(data,StandardCharsets.UTF_8);
                    for(var r:replacements.entrySet())xml=xml.replace(r.getKey(),xmlEscape(r.getValue()));
                    data=xml.getBytes(StandardCharsets.UTF_8);
                }
                zout.write(data);
                zout.closeEntry();
            }
        }
        Files.move(tmp,docx,StandardCopyOption.REPLACE_EXISTING);
    }
    private String xmlEscape(String s){return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private LinkedHashMap<String,String> replacements(CompanyInfo ex,CompanyInfo cu,String no,String date){
        LinkedHashMap<String,String> m=new LinkedHashMap<>();
        put(m,"{{CONTRACT_NO}}",no);
        put(m,"{{CONTRACT_DATE}}",date);
        put(m,"{{EXECUTOR_COMPANY}}",ex.getCompany());
        put(m,"{{EXECUTOR_ADDRESS}}",ex.getAddress());
        put(m,"{{EXECUTOR_TIN}}",ex.getVoen());
        put(m,"{{EXECUTOR_BANK_ACCOUNT}}",ex.getBankAccount());
        put(m,"{{EXECUTOR_CORRESPONDENT}}",ex.getCorrespondentAccount());
        put(m,"{{EXECUTOR_BANK_NAME}}",ex.getBankName());
        put(m,"{{EXECUTOR_BANK_TIN}}",ex.getBankVoen());
        put(m,"{{EXECUTOR_BANK_SWIFT}}",ex.getBankSwift());
        put(m,"{{EXECUTOR_BANK_CODE}}",ex.getBankCode());
        put(m,"{{EXECUTOR_DIRECTOR}}",ex.signerName());
        put(m,"{{CUSTOMER_COMPANY}}",cu.getCompany());
        put(m,"{{CUSTOMER_ADDRESS}}",cu.getAddress());
        put(m,"{{CUSTOMER_TIN}}",cu.getVoen());
        put(m,"{{CUSTOMER_BANK_ACCOUNT}}",cu.getBankAccount());
        put(m,"{{CUSTOMER_CORRESPONDENT}}",cu.getCorrespondentAccount());
        put(m,"{{CUSTOMER_BANK_NAME}}",cu.getBankName());
        put(m,"{{CUSTOMER_BANK_TIN}}",cu.getBankVoen());
        put(m,"{{CUSTOMER_BANK_SWIFT}}",cu.getBankSwift());
        put(m,"{{CUSTOMER_BANK_CODE}}",cu.getBankCode());
        put(m,"{{CUSTOMER_DIRECTOR}}",cu.signerName());
        return m;
    }
    private void replaceTable(XWPFTable t,Map<String,String> m){for(XWPFTableRow r:t.getRows())for(XWPFTableCell c:r.getTableCells()){for(XWPFParagraph p:c.getParagraphs())replace(p,m);for(XWPFTable nested:c.getTables())replaceTable(nested,m);}}
    private void replace(XWPFParagraph p,Map<String,String> m){String raw=p.getText();if(raw==null||raw.isBlank())return;String x=raw;for(var e:m.entrySet())x=x.replace(e.getKey(),e.getValue());if(x.equals(raw))return;List<XWPFRun> runs=p.getRuns();if(runs.isEmpty()){p.createRun().setText(x);return;}for(int i=runs.size()-1;i>=1;i--)p.removeRun(i);XWPFRun r=p.getRuns().get(0);r.setText(x,0);}
    private void put(Map<String,String>m,String k,String v){if(k!=null&&!k.isBlank())m.put(k,safe(v));}
    private Path unique(Path dir,String name){Path p=dir.resolve(name);if(!Files.exists(p))return p;String base=name.replaceFirst("(?i)\\.docx$","");int i=2;while(Files.exists(p=dir.resolve(base+"_"+i+".docx")))i++;return p;}
    private String safeKeepExt(String n){String x=n.replaceAll("[^A-Za-z0-9ƏÖÜĞİŞÇəöüğışç._ -]","_").trim();return x.isBlank()?"muqavile.docx":x;}
    private String safeBase(String n){String x=(n==null?"":n).replaceAll("[^A-Za-z0-9ƏÖÜĞİŞÇəöüğışç._-]","_");return x.isBlank()?"muqavile":x;}
    private String titleFromFile(String n){return n.replaceFirst("(?i)\\.docx$","").replace('_',' ');}
    private String esc(String s){return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private String first(String...xs){for(String x:xs)if(!blank(x))return x.trim();return "";}private boolean blank(String s){return s==null||s.isBlank();}private String safe(String s){return s==null?"":s;}
}
