package az.gmb.taxdata.service;

import az.gmb.taxdata.model.CompanyInfo;
import az.gmb.taxdata.util.ExcelUtil;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

@Service
public class TemplateEditorService {
    private final StorageService storage;
    private final WorkspaceDataService workspaceData;

    public TemplateEditorService(StorageService storage, WorkspaceDataService workspaceData) {
        this.storage = storage;
        this.workspaceData = workspaceData;
    }

    public Map<String,Object> editorData(String workspaceId, String type, String entityType, String requestedUploadId) throws IOException {
        String t = normalizeType(type);
        String e = normalizeEntity(entityType);
        String active = firstNonBlank(requestedUploadId, workspaceData.activeTemplate(workspaceId, t, e));
        try (XSSFWorkbook wb = open(workspaceId, t, e, active)) {
            Sheet s = wb.getSheet("ŞABLON"); if (s == null && wb.getNumberOfSheets()>0) s = wb.getSheetAt(0);
            DataFormatter f = new DataFormatter();
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            int maxRow = Math.min(Math.max(s.getLastRowNum() + 1, 35), 120);
            int detectedMaxCol = 8;
            for (int r=0;r<maxRow;r++) {
                Row rr=s.getRow(r);
                if(rr!=null && rr.getLastCellNum()>0) detectedMaxCol=Math.max(detectedMaxCol, rr.getLastCellNum());
            }
            for(int i=0;i<s.getNumMergedRegions();i++) detectedMaxCol=Math.max(detectedMaxCol, s.getMergedRegion(i).getLastColumn()+1);
            int maxCol=Math.min(Math.max(detectedMaxCol,8),26);
            List<List<Map<String,String>>> rows = new ArrayList<>();
            for (int r=0;r<maxRow;r++) {
                List<Map<String,String>> row = new ArrayList<>();
                Row rr = s.getRow(r);
                for (int c=0;c<maxCol;c++) {
                    String ref = ExcelUtil.columnName(c) + (r+1);
                    String value = rr == null ? "" : ExcelUtil.text(rr.getCell(c), f, ev);
                    row.add(Map.of("ref", ref, "value", value == null ? "" : value));
                }
                rows.add(row);
            }
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("type",t);result.put("entityType",e);result.put("activeUploadId",active==null?"":active);
            result.put("rows",rows);result.put("rowCount",maxRow);result.put("columnCount",maxCol);
            result.put("mapping",workspaceData.mapping(workspaceId,t,e));result.put("fields",fields(t));
            return result;
        }
    }

    public Map<String,Object> saveMapping(String workspaceId, String type, String entityType, Map<String,String> mapping) throws IOException {
        String t = normalizeType(type), e = normalizeEntity(entityType);
        Set<String> allowed=fields(t).keySet();
        LinkedHashMap<String,String> clean=new LinkedHashMap<>();
        if(mapping!=null) for(var entry:mapping.entrySet()){
            String key=entry.getKey(), ref=entry.getValue();
            if(key!=null && allowed.contains(key) && ref!=null && ref.matches("(?i)[A-Z]{1,3}[1-9][0-9]{0,4}")) clean.put(key,ref.toUpperCase(Locale.ROOT));
        }
        workspaceData.saveMapping(workspaceId, t, e, clean);
        return Map.of("saved", true, "mapping", workspaceData.mapping(workspaceId, t, e));
    }

    public Map<String,Object> saveStaticEdits(String workspaceId, String type, String entityType, String requestedUploadId, Map<String,String> edits) throws IOException {
        String t = normalizeType(type), e = normalizeEntity(entityType);
        String active = firstNonBlank(requestedUploadId, workspaceData.activeTemplate(workspaceId, t, e));
        byte[] bytes;
        try (XSSFWorkbook wb = open(workspaceId, t, e, active); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.getSheet("ŞABLON"); if (s == null && wb.getNumberOfSheets()>0) s = wb.getSheetAt(0);
            if (edits != null) for (var entry : edits.entrySet()) {
                if (entry.getKey() != null && entry.getKey().matches("(?i)[A-Z]{1,3}[1-9][0-9]{0,4}"))
                    ExcelUtil.setString(s, entry.getKey().toUpperCase(Locale.ROOT), entry.getValue() == null ? "" : entry.getValue());
            }
            wb.write(out); bytes = out.toByteArray();
        }
        String id = storage.saveBytes(workspaceId, bytes, "template-" + t + "-edited", t + "_" + e.toLowerCase(Locale.ROOT) + "_web_edited.xlsx");
        workspaceData.setActiveTemplate(workspaceId, t, e, id);
        return Map.of("saved", true, "uploadId", id);
    }

    public Map<String,String> fields(String type) {
        LinkedHashMap<String,String> m = new LinkedHashMap<>();
        m.put("seller.company", "İcraçı/Satıcı adı"); m.put("seller.voen", "İcraçı VÖEN");
        m.put("seller.bankName", "İcraçı bank adı"); m.put("seller.bankVoen", "İcraçı bank VÖEN");
        m.put("seller.bankSwift", "İcraçı SWIFT"); m.put("seller.bankCode", "İcraçı bank kodu");
        m.put("seller.correspondent", "İcraçı M/h"); m.put("seller.address", "İcraçı ünvanı"); m.put("seller.bankAccount", "İcraçı H/h");
        m.put("buyer.company", "Sifarişçi/Alıcı adı"); m.put("buyer.voen", "Sifarişçi VÖEN");
        m.put("buyer.bankName", "Sifarişçi bank adı"); m.put("buyer.bankVoen", "Sifarişçi bank VÖEN");
        m.put("buyer.bankSwift", "Sifarişçi SWIFT"); m.put("buyer.bankCode", "Sifarişçi bank kodu");
        m.put("buyer.correspondent", "Sifarişçi M/h"); m.put("buyer.address", "Sifarişçi ünvanı"); m.put("buyer.bankAccount", "Sifarişçi H/h");
        m.put("document.number", "Sənəd nömrəsi"); m.put("document.date", "Sənəd tarixi");
        m.put("items.start", "Mal/xidmət cədvəlinin ilk sətri");
        m.put("contract.number", "Müqavilə nömrəsi"); m.put("contract.date", "Müqavilə tarixi");
        m.put("einvoice.number", "E‑Qaimə nömrəsi"); m.put("note", "Qeyd");
        m.put("subtotal", "ƏDV-siz cəm"); m.put("vat.label", "ƏDV başlığı"); m.put("vat.amount", "ƏDV məbləği"); m.put("total", "Yekun");
        m.put("executor.signer", "İcraçı imza edən"); m.put("orderer.signer", "Sifarişçi imza edən");
        return m;
    }

    private XSSFWorkbook open(String workspaceId, String type, String entityType, String uploadId) throws IOException {
        if (uploadId != null && !uploadId.isBlank()) {
            try (InputStream in = Files.newInputStream(storage.resolveUploadFile(workspaceId, uploadId))) { return new XSSFWorkbook(in); }
        }
        try (InputStream in = new ClassPathResource("excel-templates/" + defaultName(type,entityType)).getInputStream()) { return new XSSFWorkbook(in); }
    }

    private String defaultName(String type,String entityType) { boolean individual=CompanyInfo.INDIVIDUAL.equalsIgnoreCase(entityType); return switch (type) { case "hf" -> "hesab_faktura_faktiki.xlsx"; case "qr" -> individual?"qiymet_razilasma_fiziki.xlsx":"qiymet_razilasma_faktiki.xlsx"; default -> individual?"tehvil_teslim_fiziki.xlsx":"tehvil_teslim_faktiki.xlsx"; }; }
    private String normalizeType(String type) { String t=type==null?"":type.toLowerCase(Locale.ROOT); if(!Set.of("hf","qr","tt").contains(t)) throw new IllegalArgumentException("Şablon tipi hf, qr və ya tt olmalıdır."); return t; }
    private String normalizeEntity(String entity) { return CompanyInfo.INDIVIDUAL.equalsIgnoreCase(entity) ? CompanyInfo.INDIVIDUAL : CompanyInfo.LEGAL; }
    private String firstNonBlank(String a,String b){return a!=null&&!a.isBlank()?a:b;}
}
