package az.gmb.taxdata.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.UUID;

@Service
public class StorageService {
    private final Path rootDir;
    private final Path workspacesDir;
    private final Path sessionWorkspacesDir;

    public StorageService(@Value("${app.storage.root:./data}") String root,
                          @Value("${app.storage.session-root:}") String sessionRoot) {
        this.rootDir = Paths.get(root).toAbsolutePath().normalize();
        this.workspacesDir = rootDir.resolve("workspaces");
        String sr = sessionRoot == null ? "" : sessionRoot.trim();
        this.sessionWorkspacesDir = sr.isBlank()
                ? Paths.get(System.getProperty("java.io.tmpdir"), "taxdata-browser-sessions").toAbsolutePath().normalize()
                : Paths.get(sr).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(workspacesDir);
        Files.createDirectories(sessionWorkspacesDir);
    }

    public String normalizeWorkspaceId(String raw) {
        String id = raw == null ? "" : raw.trim();
        if (id.isBlank()) return "default";
        if (!id.matches("[A-Za-z0-9_-]{8,96}")) throw new IllegalArgumentException("Yanlış istifadəçi/workspace ID.");
        return id;
    }

    public boolean isSessionWorkspace(String workspaceId) {
        return normalizeWorkspaceId(workspaceId).startsWith("sess_");
    }

    private Path workspaceBase(String workspaceId) {
        return isSessionWorkspace(workspaceId) ? sessionWorkspacesDir : workspacesDir;
    }

    public Path getWorkspaceDir(String workspaceId) throws IOException {
        String id = normalizeWorkspaceId(workspaceId);
        Path base = workspaceBase(id);
        Path p = base.resolve(id).normalize();
        if (!p.startsWith(base)) throw new IllegalArgumentException("Yanlış workspace yolu.");
        Files.createDirectories(p);
        touchWorkspace(id);
        return p;
    }

    public boolean workspaceExists(String workspaceId) {
        String id = normalizeWorkspaceId(workspaceId);
        Path base = workspaceBase(id);
        Path p = base.resolve(id).normalize();
        return p.startsWith(base) && Files.isDirectory(p);
    }

    public void touchWorkspace(String workspaceId) {
        try {
            String id = normalizeWorkspaceId(workspaceId);
            Path base = workspaceBase(id);
            Path p = base.resolve(id).normalize();
            if (p.startsWith(base) && Files.exists(p)) Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (Exception ignored) {}
    }

    public long workspaceSize(String workspaceId) throws IOException {
        if (!workspaceExists(workspaceId)) return 0L;
        String id = normalizeWorkspaceId(workspaceId);
        Path p = workspaceBase(id).resolve(id).normalize();
        try (var s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(x -> {
                try { return Files.size(x); } catch (IOException e) { return 0L; }
            }).sum();
        }
    }

    public void deleteWorkspace(String workspaceId) throws IOException {
        String id = normalizeWorkspaceId(workspaceId);
        Path base = workspaceBase(id);
        Path p = base.resolve(id).normalize();
        if (!p.startsWith(base) || !Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }

    public Path getSessionWorkspacesDir() { return sessionWorkspacesDir; }

    public Path getUploadsDir(String workspaceId) throws IOException {
        Path p = getWorkspaceDir(workspaceId).resolve("uploads");
        Files.createDirectories(p);
        return p;
    }

    public Path getOutputsDir(String workspaceId) throws IOException {
        Path p = getWorkspaceDir(workspaceId).resolve("output");
        Files.createDirectories(p);
        return p;
    }

    public Path getContractsDir(String workspaceId) throws IOException {
        Path p = getWorkspaceDir(workspaceId).resolve("contracts");
        Files.createDirectories(p);
        return p;
    }

    public Path getEfpPackagesDir(String workspaceId) throws IOException {
        Path p = getWorkspaceDir(workspaceId).resolve("efp-packages");
        Files.createDirectories(p);
        return p;
    }

    public String saveUpload(String workspaceId, MultipartFile file, String prefix) throws IOException {
        String original = file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename();
        String safe = safeFileName(original);
        String id = prefix + "-" + UUID.randomUUID();
        Path dir = getUploadsDir(workspaceId).resolve(id);
        Files.createDirectories(dir);
        file.transferTo(dir.resolve(safe));
        Files.writeString(dir.resolve("original-name.txt"), original, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        touchWorkspace(workspaceId);
        return id;
    }

    public String saveBytes(String workspaceId, byte[] bytes, String prefix, String fileName) throws IOException {
        String id = prefix + "-" + UUID.randomUUID();
        Path dir = getUploadsDir(workspaceId).resolve(id);
        Files.createDirectories(dir);
        Files.write(dir.resolve(safeFileName(fileName)), bytes, StandardOpenOption.CREATE_NEW);
        Files.writeString(dir.resolve("original-name.txt"), fileName, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        touchWorkspace(workspaceId);
        return id;
    }

    public Path resolveUploadFile(String workspaceId, String uploadId) throws IOException {
        if (uploadId == null || !uploadId.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Yanlış upload ID");
        Path base = getUploadsDir(workspaceId);
        Path dir = base.resolve(uploadId).normalize();
        if (!dir.startsWith(base) || !Files.isDirectory(dir)) throw new NoSuchFileException(uploadId);
        try (var s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals("original-name.txt"))
                    .findFirst().orElseThrow(() -> new NoSuchFileException(uploadId));
        }
    }

    public void deleteUpload(String workspaceId, String uploadId) throws IOException {
        if (uploadId == null || !uploadId.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Yanlış upload ID");
        Path base = getUploadsDir(workspaceId);
        Path dir = base.resolve(uploadId).normalize();
        if (!dir.startsWith(base) || !Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
        touchWorkspace(workspaceId);
    }

    /** Removes legacy temporary uploads such as company-requisite Excel files. */
    public void deleteUploadsByPrefix(String workspaceId, String prefix) throws IOException {
        String safePrefix = prefix == null ? "" : prefix.trim();
        if (safePrefix.isBlank() || !safePrefix.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Yanlış upload prefiksi.");
        Path base = getUploadsDir(workspaceId);
        if (!Files.isDirectory(base)) return;
        try (var entries = Files.list(base)) {
            for (Path dir : entries.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(safePrefix)).toList()) {
                try (var tree = Files.walk(dir)) {
                    for (Path x : tree.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
                }
            }
        }
        touchWorkspace(workspaceId);
    }

    public Path createOutputFolder(String workspaceId, String baseName) throws IOException {
        Path outputsDir = getOutputsDir(workspaceId);
        String safe = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path p = outputsDir.resolve(safe);
        int i = 2;
        while (Files.exists(p)) p = outputsDir.resolve(safe + "_" + i++);
        Files.createDirectories(p);
        touchWorkspace(workspaceId);
        return p;
    }

    public Path resolveOutputFolder(String workspaceId, String folder) throws IOException {
        if (folder == null || !folder.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Yanlış çıxış qovluğu.");
        Path base = getOutputsDir(workspaceId);
        Path p = base.resolve(folder).normalize();
        if (!p.startsWith(base) || !Files.isDirectory(p)) throw new NoSuchFileException(folder);
        return p;
    }

    private String safeFileName(String name) {
        return name == null ? "file.bin" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
