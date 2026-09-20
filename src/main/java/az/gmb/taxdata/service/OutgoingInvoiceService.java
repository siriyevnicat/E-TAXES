package az.gmb.taxdata.service;

import az.gmb.taxdata.model.*;
import az.gmb.taxdata.util.ExcelUtil;
import org.apache.poi.ss.usermodel.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class OutgoingInvoiceService {
    private final StorageService storage;
    private final WorkspaceDataService workspace;

    public OutgoingInvoiceService(StorageService storage, WorkspaceDataService workspace, com.fasterxml.jackson.databind.ObjectMapper ignored) {
        this.storage=storage; this.workspace=workspace;
    }

    public byte[] template() throws IOException {
        try(InputStream in=new ClassPathResource("excel-templates/efp_qaime_paketleme_sablonu.xlsx").getInputStream()){return in.readAllBytes();}
    }

    public List<OutgoingInvoice> list(String ws) throws IOException {
        return workspace.getState(ws).getOutgoingInvoices().stream()
                .sorted(Comparator.comparing(OutgoingInvoice::getInvoiceDate,Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(OutgoingInvoice::getInvoiceNumber,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
    }

    public OutgoingInvoice get(String ws,String id) throws IOException {
        return workspace.getState(ws).getOutgoingInvoices().stream().filter(x->Objects.equals(x.getId(),id)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Göndərilən qaimə tapılmadı."));
    }

    public synchronized OutgoingInvoice save(String ws, OutgoingInvoice in) throws IOException {
        normalize(in);
        WorkspaceState state=workspace.getState(ws); String invoiceKey=key(in.getInvoiceNumber()); OutgoingInvoice current=null;
        if(!blank(in.getId())) current=state.getOutgoingInvoices().stream().filter(x->Objects.equals(x.getId(),in.getId())).findFirst().orElse(null);
        for(OutgoingInvoice x:state.getOutgoingInvoices()) if(x!=current && key(x.getInvoiceNumber()).equals(invoiceKey)) throw new IllegalArgumentException("Bu göndərilən qaimə nömrəsi artıq registrdə var.");
        if(current==null){in.setId("OUT_"+UUID.randomUUID());in.setCreatedAt(LocalDateTime.now());state.getOutgoingInvoices().add(in);current=in;}
        else {int pos=state.getOutgoingInvoices().indexOf(current);in.setCreatedAt(current.getCreatedAt());state.getOutgoingInvoices().set(pos,in);current=in;}
        current.setUpdatedAt(LocalDateTime.now());workspace.saveState(ws,state);return current;
    }

    public synchronized void delete(String ws,String id) throws IOException {
        WorkspaceState state=workspace.getState(ws);if(!state.getOutgoingInvoices().removeIf(x->Objects.equals(x.getId(),id)))throw new IllegalArgumentException("Göndərilən qaimə tapılmadı.");workspace.saveState(ws,state);
    }

    public OutgoingInvoiceImportResult importExcel(String ws,String uploadId) throws IOException {
        Path path=storage.resolveUploadFile(ws,uploadId);List<OutgoingInvoice> parsed=parse(path,uploadId);
        WorkspaceState state=workspace.getState(ws);
        // Importun təkrar nəzarəti AppController-də fayl məzmunu + orijinal fayl adı ilə aparılır.
        // Buna görə fərqli adlı fayldakı eyni şirkət/məhsul/qaimə məlumatı ayrıca import kimi qəbul edilir.
        // Eyni fayl adı + eyni məzmun isə controller cache-i tərəfindən ikinci dəfə əlavə edilmir.
        int added=0,skipped=0;for(OutgoingInvoice x:parsed){x.setId("OUT_"+UUID.randomUUID());x.setManual(false);x.setCreatedAt(LocalDateTime.now());x.setUpdatedAt(LocalDateTime.now());state.getOutgoingInvoices().add(x);added++;}
        workspace.saveState(ws,state);return new OutgoingInvoiceImportResult(uploadId,parsed.size(),added,skipped,false,list(ws));
    }

    private List<OutgoingInvoice> parse(Path path,String uploadId) throws IOException {
        LinkedHashMap<String,OutgoingInvoice> map=new LinkedHashMap<>();
        try(InputStream in=Files.newInputStream(path);Workbook wb=WorkbookFactory.create(in)){
            DataFormatter fmt=new DataFormatter();FormulaEvaluator ev=wb.getCreationHelper().createFormulaEvaluator();
            String baseNo=uploadBaseName(path);int officialSheetNo=0;
            for(Sheet sh:wb){
                int numberRow=officialNumberRow(sh,fmt,ev);
                if(numberRow>=0 && looksLikeLatestOfficialTemplate(sh,fmt,ev)){
                    officialSheetNo++;
                    OutgoingInvoice q=parseLatestOfficialSheet(sh,fmt,ev,uploadId,baseNo,officialSheetNo);
                    if(q!=null && !q.getItems().isEmpty())map.put(key(q.getInvoiceNumber()),q);
                    continue;
                }
                Row header=null;Map<String,Integer> h=null;
                for(int r=sh.getFirstRowNum();r<=Math.min(sh.getLastRowNum(),60);r++){
                    Row row=sh.getRow(r);if(row==null)continue;Map<String,Integer> m=header(row,fmt,ev);
                    if(m.containsKey("number")&&m.containsKey("buyerVoen")&&m.containsKey("name")){header=row;h=m;break;}
                }
                if(header==null)continue;
                for(int r=header.getRowNum()+1;r<=sh.getLastRowNum();r++){
                    Row row=sh.getRow(r);if(row==null)continue;String no=text(row,h,"number",fmt,ev).trim();if(no.isBlank())continue;
                    OutgoingInvoice q=map.computeIfAbsent(key(no),k->{OutgoingInvoice z=new OutgoingInvoice();z.setInvoiceNumber(no);z.setSourceUploadId(uploadId);z.setManual(false);return z;});
                    if(q.getInvoiceDate()==null)q.setInvoiceDate(date(row,h,"date",fmt,ev));
                    if(blank(q.getBuyerVoen()))q.setBuyerVoen(text(row,h,"buyerVoen",fmt,ev));if(blank(q.getBuyerName()))q.setBuyerName(text(row,h,"buyer",fmt,ev));
                    if(blank(q.getSellerVoen()))q.setSellerVoen(text(row,h,"sellerVoen",fmt,ev));if(blank(q.getSellerName()))q.setSellerName(text(row,h,"seller",fmt,ev));
                    if(blank(q.getBasis()))q.setBasis(text(row,h,"basis",fmt,ev));if(blank(q.getNote()))q.setNote(ExcelUtil.cleanNotePrefix(text(row,h,"note",fmt,ev)));
                    if(blank(q.getObjectName()))q.setObjectName(text(row,h,"objectName",fmt,ev));if(blank(q.getObjectCode()))q.setObjectCode(text(row,h,"objectCode",fmt,ev));
                    String name=text(row,h,"name",fmt,ev);if(name.isBlank())continue;OutgoingInvoiceItem it=new OutgoingInvoiceItem();
                    it.setCode(text(row,h,"code",fmt,ev));it.setName(name);it.setGtin(blankTo(text(row,h,"gtin",fmt,ev),"0"));it.setUnit(blankTo(text(row,h,"unit",fmt,ev),"Ədəd"));
                    it.setQuantity(dec(row,h,"qty",fmt,ev));if(it.getQuantity().signum()==0)it.setQuantity(BigDecimal.ONE);it.setUnitPrice(dec(row,h,"price",fmt,ev));it.setAmount(dec(row,h,"amount",fmt,ev));
                    it.setExciseRate(dec(row,h,"exciseRate",fmt,ev));it.setExciseAmount(dec(row,h,"excise",fmt,ev));it.setTotalAmount(dec(row,h,"totalAmount",fmt,ev));
                    it.setVatTaxableAmount(dec(row,h,"vatTaxable",fmt,ev));it.setVatNonTaxableAmount(dec(row,h,"vatNonTaxable",fmt,ev));it.setVatExemptAmount(dec(row,h,"vatExempt",fmt,ev));it.setVatZeroAmount(dec(row,h,"vatZero",fmt,ev));
                    it.setVatAmount(dec(row,h,"vat",fmt,ev));it.setRoadTaxAmount(dec(row,h,"roadTax",fmt,ev));it.setFinalAmount(dec(row,h,"finalAmount",fmt,ev));
                    boolean officialValues=false;
                    for(String officialKey:List.of("exciseRate","excise","totalAmount","vatTaxable","vatNonTaxable","vatExempt","vatZero","vat","roadTax","finalAmount")){
                        if(!text(row,h,officialKey,fmt,ev).isBlank()){officialValues=true;break;}
                    }
                    it.setOfficialFields(officialValues);BigDecimal vr=dec(row,h,"vatRate",fmt,ev);if(vr.signum()>0)it.setVatRate(vr);q.getItems().add(it);
                }
            }
        }
        List<OutgoingInvoice> out=new ArrayList<>(map.values());for(OutgoingInvoice q:out)normalize(q);return out;
    }

    private boolean looksLikeLatestOfficialTemplate(Sheet sh,DataFormatter fmt,FormulaEvaluator ev){
        String a1=ExcelUtil.norm(cellText(sh,0,0,fmt,ev));String a2=ExcelUtil.norm(cellText(sh,1,0,fmt,ev));
        String a3=ExcelUtil.norm(cellText(sh,2,0,fmt,ev));String a4=ExcelUtil.norm(cellText(sh,3,0,fmt,ev));
        return a1.contains("gonderen")&&a2.contains("qebul eden")&&a3.contains("esas")&&a4.contains("elave qeyd");
    }

    private int officialNumberRow(Sheet sh,DataFormatter fmt,FormulaEvaluator ev){
        int last=Math.min(sh.getLastRowNum(),20);
        for(int r=sh.getFirstRowNum();r<=last;r++){
            int matched=0;for(int c=0;c<18;c++)if(Integer.toString(c+1).equals(cellText(sh,r,c,fmt,ev).replace(".0","")))matched++;
            if(matched>=16)return r;
        }
        return -1;
    }

    private OutgoingInvoice parseLatestOfficialSheet(Sheet sh,DataFormatter fmt,FormulaEvaluator ev,String uploadId,String baseNo,int sheetNo){
        int numberRow=officialNumberRow(sh,fmt,ev);if(numberRow<0)return null;
        OutgoingInvoice q=new OutgoingInvoice();
        q.setInvoiceNumber(sheetNo<=1?baseNo:baseNo+"-"+sheetNo);q.setInvoiceDate(LocalDate.now());q.setSourceUploadId(uploadId);q.setManual(false);
        q.setSellerName(cellText(sh,0,1,fmt,ev));q.setSellerVoen(cellText(sh,0,3,fmt,ev));
        q.setBuyerName(cellText(sh,1,1,fmt,ev));q.setBuyerVoen(cellText(sh,1,3,fmt,ev));
        q.setBasis(cellText(sh,2,1,fmt,ev));q.setNote(ExcelUtil.cleanNotePrefix(cellText(sh,3,1,fmt,ev)));
        for(int r=numberRow+1;r<=sh.getLastRowNum();r++){
            Row row=sh.getRow(r);if(row==null)continue;String name=cellText(row,1,fmt,ev);String code=cellText(row,2,fmt,ev);if(name.isBlank()&&code.isBlank())continue;
            OutgoingInvoiceItem it=new OutgoingInvoiceItem();it.setName(name);it.setCode(code);it.setGtin(blankTo(cellText(row,3,fmt,ev),"0"));it.setUnit(blankTo(cellText(row,4,fmt,ev),"Ədəd"));
            it.setQuantity(cellDec(row,5,fmt,ev));if(it.getQuantity().signum()==0)it.setQuantity(BigDecimal.ONE);it.setUnitPrice(cellDec(row,6,fmt,ev));it.setAmount(cellDec(row,7,fmt,ev));
            it.setExciseRate(cellDec(row,8,fmt,ev));it.setExciseAmount(cellDec(row,9,fmt,ev));it.setTotalAmount(cellDec(row,10,fmt,ev));it.setVatTaxableAmount(cellDec(row,11,fmt,ev));
            it.setVatZeroAmount(cellDec(row,12,fmt,ev));it.setVatExemptAmount(cellDec(row,13,fmt,ev));it.setVatNonTaxableAmount(cellDec(row,14,fmt,ev));it.setVatAmount(cellDec(row,15,fmt,ev));it.setRoadTaxAmount(cellDec(row,16,fmt,ev));it.setFinalAmount(cellDec(row,17,fmt,ev));
            boolean officialValues=false;for(int c=7;c<18;c++)if(!cellText(row,c,fmt,ev).isBlank()){officialValues=true;break;}it.setOfficialFields(officialValues);it.setVatRate(new BigDecimal("18"));q.getItems().add(it);
        }
        return q;
    }

    private String uploadBaseName(Path path){
        String n=path.getFileName().toString();try{Path marker=path.getParent().resolve("original-name.txt");if(Files.exists(marker)){String x=Files.readString(marker).trim();if(!x.isBlank())n=x;}}catch(Exception ignored){}
        n=n.replaceFirst("(?i)\\.(xlsx|xls)$","").trim();return n.isBlank()?"Imported-"+System.currentTimeMillis():n;
    }
    private String cellText(Sheet sh,int r,int c,DataFormatter f,FormulaEvaluator ev){Row row=sh.getRow(r);return row==null?"":cellText(row,c,f,ev);}
    private String cellText(Row row,int c,DataFormatter f,FormulaEvaluator ev){return row==null?"":ExcelUtil.text(row.getCell(c),f,ev).trim();}
    private BigDecimal cellDec(Row row,int c,DataFormatter f,FormulaEvaluator ev){return ExcelUtil.decimal(cellText(row,c,f,ev));}

    public Map<String,Object> packageInvoices(String ws,List<String> ids) throws Exception {
        if(ids==null||ids.isEmpty())throw new IllegalArgumentException("Paket üçün ən azı bir göndərilən qaimə seçin.");
        List<OutgoingInvoice> selected=new ArrayList<>();for(String id:ids){OutgoingInvoice q=get(ws,id);normalize(q);validateForPackage(q);selected.add(q);}
        String folder="efp_"+System.currentTimeMillis();Path dir=storage.getEfpPackagesDir(ws).resolve(folder);Files.createDirectories(dir);List<String> xmlNames=new ArrayList<>();
        int efpFileNo=1;
        for(OutgoingInvoice q:selected){
            // eFP / e-Taxes uyğunluğu üçün ZIP daxilində XML adı yalnız sübut olunmuş sadə ASCII formatında saxlanılır.
            // Qaimənin real registr nömrəsi məlumat modelində qalır; paket fayl adında istifadə edilmir.
            String name="gonderilen_qaime_sablonu ("+(efpFileNo++)+").xml";
            Files.write(dir.resolve(name),xml(q));xmlNames.add(name);q.setStatus("PACKAGED");q.setUpdatedAt(LocalDateTime.now());
        }
        Path zip=storage.getEfpPackagesDir(ws).resolve(folder+".zip");zipEfp(dir,zip,xmlNames);
        WorkspaceState state=workspace.getState(ws);for(OutgoingInvoice q:selected)for(int i=0;i<state.getOutgoingInvoices().size();i++)if(Objects.equals(state.getOutgoingInvoices().get(i).getId(),q.getId()))state.getOutgoingInvoices().set(i,q);workspace.saveState(ws,state);
        List<String> packageFiles=new ArrayList<>();packageFiles.add("vhf-inf/vhf.mf");packageFiles.addAll(xmlNames);
        return Map.of("folder",folder,"file",zip.getFileName().toString(),"downloadUrl","/api/outgoing/package/"+zip.getFileName(),"invoiceCount",selected.size(),"schemaStatus","QAIME_1 / version 304 + VHF manifest","files",packageFiles);
    }

    private void validateForPackage(OutgoingInvoice q){
        if(!digits(q.getBuyerVoen()).matches("\\d{10}"))throw new IllegalArgumentException(q.getInvoiceNumber()+": Alan tərəfin VÖEN-i 10 rəqəm olmalıdır.");
        if(!digits(q.getSellerVoen()).matches("\\d{10}"))throw new IllegalArgumentException(q.getInvoiceNumber()+": Satan tərəfin VÖEN-i 10 rəqəm olmalıdır.");
        if(q.getItems()==null||q.getItems().isEmpty())throw new IllegalArgumentException(q.getInvoiceNumber()+": ən azı bir mal/xidmət sətri olmalıdır.");
        int n=1;for(OutgoingInvoiceItem it:q.getItems()){if(blank(it.getCode()))throw new IllegalArgumentException(q.getInvoiceNumber()+": "+n+"-ci sətirdə mal kodu boşdur.");if(blank(it.getName()))throw new IllegalArgumentException(q.getInvoiceNumber()+": "+n+"-ci sətirdə mal adı boşdur.");if(it.getQuantity().signum()<=0)throw new IllegalArgumentException(q.getInvoiceNumber()+": "+n+"-ci sətirdə miqdar 0-dan böyük olmalıdır.");n++;}
    }

    private static final String QAIME_XSL = """
<xsl:stylesheet id="stylesheet" version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" > <xsl:template match="xsl:stylesheet" /> <xsl:template match="/root"> <html> <head> <style> body {background-color: white; font-family:  Arial, sans-serif; } .paper {padding:5px; } table {width: 100%; font-size: 16px; } table tr td {padding: 10px 15px; text-align: left; width:50%; } .products table {border-collapse: collapse; font-size: 14px; } .products table th, #products table td  {border: 1px solid #000; padding: 10px; } .products table td {width:auto; border: 1px solid #000; text-align:center; } .products table th {text-align:center; } .noPadding {padding: 40px 0px; } .total tr :nth-child(odd) {width:40%; } .total tr :nth-child(even) {width:10%; } </style> </head> <body> <table class="paper"> <tr> <td>Alan tərəfin VÖEN-i:</td> <td><xsl:value-of select="qaimeKime"/></td> </tr> <tr> <td>Alan tərəfin adı:</td> <td><xsl:value-of select="qaimeKimeAd"/></td> </tr> <tr> <td>Satan tərəfin VÖEN-i:</td> <td><xsl:value-of select="qaimeKimden"/></td> </tr> <tr> <td>Qeyd</td> <td><xsl:value-of select="des"/></td> </tr> <tr> <td>Əlavə qeyd</td> <td><xsl:value-of select="des2"/></td> </tr> <tr> <td>Obyektin adı</td> <td><xsl:value-of select="ma"/></td> </tr> <tr> <td>Obyektin kodu</td> <td><xsl:value-of select="mk"/></td> </tr> <tr> <td class="products noPadding" colspan="2"> <table> <thead> <th>Mal kodu</th> <th>Mal adı</th> <th>Bar kod</th> <th>Ölçü vahidi</th> <th>Malın miqdarı</th> <th>Malın buraxılış qiyməti</th> <th>Cəmi qiyməti</th> <th>Aksiz dərəcəsi</th> <th>Aksiz məbləği</th> <th>Cəmi məbləğ</th> <th>ƏDV-yə cəlb edilən məbləğ</th> <th>ƏDV-yə cəlb edilməyən məbləğ</th> <th>ƏDV-dən azad olunan</th> <th>ƏDV-yə 0 dərəcə ilə cəlb edilən məbləğ</th> <th>Ödənilməli ƏDV</th> <th>Yol vergisi məbləği</th> <th>Yekun məbləğ</th> </thead> <tbody class="productTable"> <xsl:for-each select="product/qaimeTable/row"> <tr> <td><xsl:value-of select="c1"/></td> <td><xsl:value-of select="c2"/></td> <td><xsl:value-of select="c17"/></td> <td><xsl:value-of select="c3"/></td> <td><xsl:value-of select="c4"/></td> <td><xsl:value-of select="c5"/></td> <td><xsl:value-of select="c6"/></td> <td><xsl:value-of select="c7"/></td> <td><xsl:value-of select="c8"/></td> <td><xsl:value-of select="c9"/></td> <td><xsl:value-of select="c10"/></td> <td><xsl:value-of select="c11"/></td> <td><xsl:value-of select="c12"/></td> <td><xsl:value-of select="c13"/></td> <td><xsl:value-of select="c14"/></td> <td><xsl:value-of select="c15"/></td> <td><xsl:value-of select="c16"/></td> </tr> </xsl:for-each> </tbody> </table> </td> </tr> </table> <table class="total"> <tr> <td>Malların cəmi qiyməti</td> <td><xsl:value-of select="product/qaimeYekunTable/row/c1"/></td> <td>Malların cəmi məbləği</td> <td><xsl:value-of select="product/qaimeYekunTable/row/c3"/></td> </tr> <tr> <td>Malların aksiz cəmi məbləği</td> <td><xsl:value-of select="product/qaimeYekunTable/row/c2"/></td> <td>Malların ƏDV-yə cəlb edilən cəmi məbləği</td> <td><xsl:value-of select="product/qaimeYekunTable/row/c4"/></td> </tr> <tr> <td>Malların cəmi ödənilməli ƏDV məbləği</td> <td><xsl:value-of select="product/qaimeYekunTable/row/c8"/></td> <td>Malların  ƏDV-yə cəlb edilməyən cəmi məbləği </td> <td><xsl:value-of select="product/qaimeYekunTable/row/c5"/></td> </tr> <tr> <td>ƏDV-dən azad olunan</td> <td><xsl:value-of select="product/qaimeYekunTable/row/c6"/></td> <td>Malların  ƏDV-yə 0 dərəcə ilə cəlb edilən cəmi məbləği</td> <td><xsl:value-of select="product/qaimeYekunTable/row/c7"/></td> </tr> <tr> <td>Yekun məbləğ</td> <td><xsl:value-of select="product/qaimeYekunTable/row/c9"/></td> <td></td> <td></td> </tr> </table> </body> </html> </xsl:template> </xsl:stylesheet>
""".strip();

    private byte[] xml(OutgoingInvoice q) {
        StringBuilder x=new StringBuilder(8192);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<?xml-stylesheet type=\"text/xsl\" href=\"#stylesheet\"?>\n");
        x.append("<!DOCTYPE root [\n<!ATTLIST xsl:stylesheet\nid ID #REQUIRED>\n]>\n");
        x.append("<root version =\"304\" kod= \"QAIME_1\"  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation= \"QAIME_1.xsd\" >\n");
        x.append(QAIME_XSL).append('\t').append("<qaimeKime>").append(xmlEscape(digits(q.getBuyerVoen()))).append("</qaimeKime>");
        tag(x,"qaimeKimden",digits(q.getSellerVoen()),1);tag(x,"ds","",1);tag(x,"dn","",1);tag(x,"des",safe(q.getBasis()),1);tag(x,"des2",safe(q.getNote()),1);tag(x,"ma",safe(q.getObjectName()),1);tag(x,"mk",safe(q.getObjectCode()),1);
        x.append("\n\t<product>\n\t\t<qaimeTable>");int idx=0;
        for(OutgoingInvoiceItem it:q.getItems()){
            x.append("\n\t\t\t<row no = '").append(idx).append("'>");
            tag(x,"c1",safe(it.getCode()),4);tag(x,"c2",safe(it.getName()),4);tag(x,"c3",safe(it.getUnit()),4);tag(x,"c4",num(it.getQuantity()),4);tag(x,"c5",num(it.getUnitPrice()),4);tag(x,"c6",fixed(it.getAmount(),4),4);tag(x,"c7",num(it.getExciseRate()),4);tag(x,"c8",num(it.getExciseAmount()),4);tag(x,"c9",fixed(it.getTotalAmount(),4),4);tag(x,"c10",num(it.getVatTaxableAmount()),4);tag(x,"c11",num(it.getVatNonTaxableAmount()),4);tag(x,"c12",num(it.getVatExemptAmount()),4);tag(x,"c13",num(it.getVatZeroAmount()),4);tag(x,"c14",fixed(it.getVatAmount(),4),4);tag(x,"c15",num(it.getRoadTaxAmount()),4);tag(x,"c16",fixed(it.getFinalAmount(),4),4);tag(x,"c17",blank(it.getGtin())?"0":it.getGtin(),4);tag(x,"productId","0",4);
            x.append("\n\t\t\t</row>");idx++;
        }
        x.append("\n\t\t</qaimeTable>\n\t\t<qaimeYekunTable>\n\t\t\t<row>");
        tag(x,"c1",fixed(sum(q,OutgoingInvoiceItem::getAmount),2),4);tag(x,"c2",num(sum(q,OutgoingInvoiceItem::getExciseAmount)),4);tag(x,"c3",fixed(sum(q,OutgoingInvoiceItem::getTotalAmount),2),4);tag(x,"c4",num(sum(q,OutgoingInvoiceItem::getVatTaxableAmount)),4);tag(x,"c5",num(sum(q,OutgoingInvoiceItem::getVatNonTaxableAmount)),4);tag(x,"c6",num(sum(q,OutgoingInvoiceItem::getVatExemptAmount)),4);tag(x,"c7",num(sum(q,OutgoingInvoiceItem::getVatZeroAmount)),4);tag(x,"c8",fixed(sum(q,OutgoingInvoiceItem::getVatAmount),2),4);tag(x,"c9",fixed(sum(q,OutgoingInvoiceItem::getFinalAmount),2),4);tag(x,"c10",num(sum(q,OutgoingInvoiceItem::getRoadTaxAmount)),4);
        x.append("\n\t\t\t</row>\n\t\t</qaimeYekunTable>\n\t</product>\n</root>\n");
        return x.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void tag(StringBuilder x,String name,String value,int tabs){x.append('\n').append("\t".repeat(Math.max(0,tabs))).append('<').append(name).append('>').append(xmlEscape(value)).append("</").append(name).append('>');}
    private String xmlEscape(String value){
        String s=safe(value)
                .replace('\u00A0',' ')  // non-breaking space
                .replace('\u202F',' ')  // narrow non-breaking space
                .replace('\u2007',' '); // figure space
        StringBuilder out=new StringBuilder(s.length()+16);
        for(int i=0;i<s.length();){
            int cp=s.codePointAt(i);i+=Character.charCount(cp);
            if(!(cp==0x9||cp==0xA||cp==0xD||(cp>=0x20&&cp<=0xD7FF)||(cp>=0xE000&&cp<=0xFFFD)||(cp>=0x10000&&cp<=0x10FFFF)))continue;
            switch(cp){
                case '&'->out.append("&amp;");
                case '<'->out.append("&lt;");
                case '>'->out.append("&gt;");
                case '\"'->out.append("&quot;");
                case '\''->out.append("&apos;");
                default->out.appendCodePoint(cp);
            }
        }
        return out.toString();
    }
    private String fixed(BigDecimal x,int scale){return nz(x).setScale(scale,RoundingMode.HALF_UP).toPlainString();}

    private interface AmountGetter{BigDecimal get(OutgoingInvoiceItem it);}private BigDecimal sum(OutgoingInvoice q,AmountGetter g){BigDecimal x=BigDecimal.ZERO;for(OutgoingInvoiceItem it:q.getItems())x=x.add(nz(g.get(it)));return x;}
    private void add(org.w3c.dom.Document d,Element p,String n,String v){Element e=d.createElement(n);e.setTextContent(v==null?"":v);p.appendChild(e);}

    private void normalize(OutgoingInvoice q){
        if(blank(q.getInvoiceNumber()))throw new IllegalArgumentException("Qaimə nömrəsi boş ola bilməz.");if(q.getInvoiceDate()==null)q.setInvoiceDate(LocalDate.now());if(q.getItems()==null)q.setItems(new ArrayList<>());BigDecimal sub=BigDecimal.ZERO,vat=BigDecimal.ZERO,total=BigDecimal.ZERO;
        for(OutgoingInvoiceItem it:q.getItems()){if(it==null)continue;if(it.getQuantity()==null||it.getQuantity().signum()==0)it.setQuantity(BigDecimal.ONE);if(it.getUnitPrice()==null)it.setUnitPrice(BigDecimal.ZERO);if(blank(it.getUnit()))it.setUnit("Ədəd");if(blank(it.getGtin()))it.setGtin("0");
            if(!it.isOfficialFields()){
                if(it.getAmount()==null||it.getAmount().signum()==0)it.setAmount(it.getQuantity().multiply(nz(it.getUnitPrice())));if(it.getTotalAmount()==null||it.getTotalAmount().signum()==0)it.setTotalAmount(it.getAmount().add(nz(it.getExciseAmount())));
                boolean noVatAllocation=nz(it.getVatTaxableAmount()).signum()==0&&nz(it.getVatNonTaxableAmount()).signum()==0&&nz(it.getVatExemptAmount()).signum()==0&&nz(it.getVatZeroAmount()).signum()==0;
                if(noVatAllocation)it.setVatTaxableAmount(it.getTotalAmount());
                if((it.getVatAmount()==null||it.getVatAmount().signum()==0)&&nz(it.getVatRate()).signum()>0&&it.getVatTaxableAmount().signum()>0)it.setVatAmount(it.getVatTaxableAmount().multiply(it.getVatRate()).divide(new BigDecimal("100"),4,RoundingMode.HALF_UP));
                if(it.getFinalAmount()==null||it.getFinalAmount().signum()==0)it.setFinalAmount(it.getTotalAmount().add(nz(it.getVatAmount())).add(nz(it.getRoadTaxAmount())));
            }
            sub=sub.add(nz(it.getTotalAmount()));vat=vat.add(nz(it.getVatAmount()));total=total.add(nz(it.getFinalAmount()));
        }
        q.setSubtotal(sub.setScale(2,RoundingMode.HALF_UP));q.setVat(vat.setScale(2,RoundingMode.HALF_UP));q.setTotal(total.setScale(2,RoundingMode.HALF_UP));q.setNote(ExcelUtil.cleanNotePrefix(q.getNote()));q.setUpdatedAt(LocalDateTime.now());
    }

    private Map<String,Integer> header(Row row,DataFormatter f,FormulaEvaluator ev){Map<String,Integer> m=new HashMap<>();for(Cell c:row){String n=ExcelUtil.norm(ExcelUtil.text(c,f,ev));String k=null;
        if(n.equals("qaime nomresi")||n.equals("qaime no")||n.equals("nomre")||n.contains("seriya nomresi"))k="number";else if(n.equals("tarix")||n.contains("qaime tarixi"))k="date";
        else if(n.contains("alan terefin voen" )||n.contains("qebul eden")&&n.contains("voen")||n.contains("alici")&&n.contains("voen"))k="buyerVoen";
        else if(n.contains("satan terefin voen")||n.contains("gonderen")&&n.contains("voen")||n.contains("satici")&&n.contains("voen"))k="sellerVoen";
        else if(n.contains("alan terefin adi")||n.equals("alici")||n.contains("qebul eden"))k="buyer";else if(n.contains("satan terefin adi")||n.equals("satici")||n.contains("gonderen"))k="seller";
        else if(n.equals("qeyd")||n.equals("esas")||n.contains("esas sened"))k="basis";else if(n.contains("elave qeyd"))k="note";else if(n.contains("obyektin adi"))k="objectName";else if(n.contains("obyektin kodu"))k="objectCode";
        else if(n.equals("mal kodu")||(n.contains("mal")||n.contains("xidmet"))&&n.contains("kod"))k="code";else if(n.equals("mal adi")||(n.contains("mal")||n.contains("xidmet"))&&n.contains("adi"))k="name";else if(n.contains("bar kod")||n.contains("gtin"))k="gtin";else if(n.contains("olcu vahidi")||n.equals("vahid"))k="unit";else if(n.contains("malin miqdari")||n.equals("miqdar")||n.contains("hecm"))k="qty";
        else if(n.contains("malin buraxilis qiymeti")||n.contains("vahid satis qiymeti")||n.equals("qiymet"))k="price";else if(n.equals("cemi qiymeti")||n.equals("mebleg"))k="amount";else if(n.contains("aksiz derecesi"))k="exciseRate";else if(n.contains("aksiz meblegi"))k="excise";else if(n.equals("cemi mebleg"))k="totalAmount";
        else if(n.contains("edv ye celb edilen mebleg"))k="vatTaxable";else if(n.contains("edv ye celb edilmeyen mebleg"))k="vatNonTaxable";else if(n.contains("edv den azad olunan"))k="vatExempt";else if(n.contains("edv ye 0 derece"))k="vatZero";else if(n.contains("odenilmeli edv"))k="vat";else if(n.contains("yol vergisi"))k="roadTax";else if(n.contains("yekun mebleg"))k="finalAmount";else if(n.contains("edv")&&(n.contains("derece")||n.contains("faiz")))k="vatRate";
        if(k!=null)m.putIfAbsent(k,c.getColumnIndex());}return m;}
    private String text(Row r,Map<String,Integer> h,String k,DataFormatter f,FormulaEvaluator ev){Integer c=h.get(k);return c==null?"":ExcelUtil.text(r.getCell(c),f,ev).trim();}
    private BigDecimal dec(Row r,Map<String,Integer> h,String k,DataFormatter f,FormulaEvaluator ev){Integer c=h.get(k);return c==null?BigDecimal.ZERO:ExcelUtil.decimal(ExcelUtil.text(r.getCell(c),f,ev));}
    private LocalDate date(Row r,Map<String,Integer> h,String k,DataFormatter f,FormulaEvaluator ev){Integer c=h.get(k);return c==null?null:ExcelUtil.date(r.getCell(c),f,ev);}
    private String key(String s){return safe(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9əöüğışç]","");}private boolean blank(String s){return s==null||s.isBlank();}private String safe(String s){return s==null?"":s;}private String blankTo(String s,String d){return blank(s)?d:s;}private String digits(String s){return safe(s).replaceAll("\\D","");}private BigDecimal nz(BigDecimal x){return x==null?BigDecimal.ZERO:x;}private String num(BigDecimal x){return nz(x).stripTrailingZeros().toPlainString();}private String safeFile(String s){String x=safe(s).replace('\\','_').replace('/','_').replace(':','_').replace('*','_').replace('?','_').replace('\"','_').replace('<','_').replace('>','_').replace('|','_').replaceAll("\\p{Cntrl}","_").trim();while(x.endsWith("."))x=x.substring(0,x.length()-1).trim();return x.isBlank()||x.equals(".")||x.equals("..")?"qaime":x;}
    private void zipEfp(Path dir,Path zipFile,List<String> xmlNames)throws IOException{
        byte[] manifest="VHF-Manifest-Version: 1.0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(zipFile),java.nio.charset.StandardCharsets.UTF_8)){
            z.putNextEntry(new ZipEntry("vhf-inf/vhf.mf"));z.write(manifest);z.closeEntry();
            for(String name:xmlNames){Path p=dir.resolve(name);z.putNextEntry(new ZipEntry(name));Files.copy(p,z);z.closeEntry();}
        }
    }
}
