package az.gmb.taxdata.service;

import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.zip.*;

@Service
public class WorkspaceArchiveService {
    private final StorageService storage;
    public WorkspaceArchiveService(StorageService storage) { this.storage = storage; }

    public byte[] exportWorkspace(String workspaceId) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeWorkspaceZip(workspaceId,bytes); return bytes.toByteArray();
    }

    /** Streaming export used by browser local-first snapshots to avoid a second large in-memory copy. */
    public void writeWorkspaceZip(String workspaceId, OutputStream output) throws IOException {
        Path root = storage.getWorkspaceDir(workspaceId);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output)); var stream = Files.walk(root)) {
            // XLSX/DOCX/ZIP are already compressed; BEST_SPEED saves CPU and makes auto-backup much faster.
            zip.setLevel(Deflater.BEST_SPEED);
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\','/');
                zip.putNextEntry(new ZipEntry(rel)); Files.copy(p, zip); zip.closeEntry();
            }
            zip.finish();
        }
    }

    public synchronized void importWorkspace(String workspaceId, InputStream input) throws IOException {
        Path temp = Files.createTempDirectory("taxdata-import-");
        try {
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(input))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    Path target = temp.resolve(e.getName()).normalize();
                    if (!target.startsWith(temp)) throw new IllegalArgumentException("Arxivdə təhlükəli fayl yolu var.");
                    Files.createDirectories(target.getParent()); Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (!Files.exists(temp.resolve("workspace-state.json"))) throw new IllegalArgumentException("Bu fayl E-Qaimə workspace exportu deyil.");
            Path root = storage.getWorkspaceDir(workspaceId);
            try (var stream = Files.list(root)) {
                for (Path p : stream.toList()) deleteRecursive(p);
            }
            try (var stream = Files.walk(temp)) {
                for (Path p : stream.sorted().toList()) {
                    if (p.equals(temp)) continue;
                    Path dest = root.resolve(temp.relativize(p).toString()).normalize();
                    if (Files.isDirectory(p)) Files.createDirectories(dest);
                    else { Files.createDirectories(dest.getParent()); Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING); }
                }
            }
            storage.touchWorkspace(workspaceId);
        } finally { deleteRecursive(temp); }
    }

    private void deleteRecursive(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return;
        try (var stream = Files.walk(p)) {
            for (Path x : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }
}
