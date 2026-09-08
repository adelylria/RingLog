package com.adelylria.ringlog.importexport;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Owns either a direct workbook or a private, bounded extraction of a native ZIP.
 * The direct source is never deleted; packaged temporary content is deleted on close.
 */
public final class ImportSource implements AutoCloseable {

    static final long MAX_WORKBOOK_BYTES = 128_000_000L;
    static final long MAX_ARCHIVE_BYTES = 256_000_000L;
    static final long MAX_ARCHIVE_ENTRY_BYTES = 128_000_000L;
    static final long MAX_EXTRACTED_BYTES = 512_000_000L;
    static final int MAX_ARCHIVE_ENTRIES = 512;

    private static final String NATIVE_WORKBOOK_NAME = "ringlog-export.xlsx";

    private final Path sourcePath;
    private final Path workbookPath;
    private final Path mediaRoot;
    private final Path temporaryRoot;
    private final boolean packaged;
    private boolean closed;

    private ImportSource(
            Path sourcePath,
            Path workbookPath,
            Path mediaRoot,
            Path temporaryRoot,
            boolean packaged
    ) {
        this.sourcePath = sourcePath;
        this.workbookPath = workbookPath;
        this.mediaRoot = mediaRoot;
        this.temporaryRoot = temporaryRoot;
        this.packaged = packaged;
    }

    public static ImportSource open(Path source) throws ImportValidationException {
        if (source == null) {
            throw new ImportValidationException("Selecciona un archivo XLSX o ZIP para importar.");
        }
        Path normalized = source.toAbsolutePath().normalize();
        validateInputFile(normalized);
        String name = normalized.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx")) {
            long size = fileSize(normalized);
            if (size > MAX_WORKBOOK_BYTES) {
                throw new ImportValidationException("El workbook supera el tamaño máximo seguro.");
            }
            Path parent = normalized.getParent();
            return new ImportSource(normalized, normalized, parent, null, false);
        }
        if (name.endsWith(".zip")) {
            long size = fileSize(normalized);
            if (size > MAX_ARCHIVE_BYTES) {
                throw new ImportValidationException("El ZIP supera el tamaño máximo seguro.");
            }
            return extractArchive(normalized);
        }
        throw new ImportValidationException("El archivo debe tener formato .xlsx o .zip.");
    }

    public Path sourcePath() {
        ensureOpen();
        return sourcePath;
    }

    public Path workbookPath() {
        ensureOpen();
        return workbookPath;
    }

    public Path mediaRoot() {
        ensureOpen();
        return mediaRoot;
    }

    public boolean packaged() {
        ensureOpen();
        return packaged;
    }

    public Path resolveMedia(String relativePath) throws ImportValidationException {
        ensureOpen();
        if (relativePath == null || relativePath.isBlank()) {
            throw new ImportValidationException("La ruta de la fotografía está vacía.");
        }
        String portable = validatePortablePath(relativePath, "fotografía");
        Path target = mediaRoot.resolve(portable).normalize();
        if (!target.startsWith(mediaRoot)
                || Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ImportValidationException(
                    "No se encontró una fotografía regular dentro del paquete: " + relativePath
            );
        }
        try {
            Path realRoot = mediaRoot.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot)) {
                throw new ImportValidationException(
                        "La ruta de la fotografía sale del directorio permitido: " + relativePath
                );
            }
            return realTarget;
        } catch (IOException exception) {
            throw new ImportValidationException(
                    "No se pudo validar la ruta física de la fotografía: " + relativePath,
                    exception
            );
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (temporaryRoot != null) {
            deleteTree(temporaryRoot);
        }
    }

    private static ImportSource extractArchive(Path archive) throws ImportValidationException {
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory("ringlog-import-").toAbsolutePath().normalize();
            Set<String> names = new HashSet<>();
            int entries = 0;
            int workbooks = 0;
            long extracted = 0;
            try (InputStream file = Files.newInputStream(archive);
                 ZipInputStream zip = new ZipInputStream(new BufferedInputStream(file))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entries++;
                    if (entries > MAX_ARCHIVE_ENTRIES) {
                        throw new ImportValidationException("El ZIP contiene demasiadas entradas.");
                    }
                    String name = validateArchiveEntryName(entry.getName());
                    String identity = name.toLowerCase(Locale.ROOT);
                    if (!names.add(identity)) {
                        throw new ImportValidationException(
                                "El ZIP contiene una ruta duplicada o ambigua: " + name
                        );
                    }

                    if (entry.isDirectory()) {
                        if (!("photos/".equals(name) || name.startsWith("photos/"))) {
                            throw new ImportValidationException(
                                    "El ZIP contiene una carpeta inesperada en la raíz: " + name
                            );
                        }
                        zip.closeEntry();
                        continue;
                    }

                    if (NATIVE_WORKBOOK_NAME.equals(name)) {
                        workbooks++;
                    } else if (!name.startsWith("photos/")) {
                        throw new ImportValidationException(
                                "El ZIP contiene un archivo inesperado en la raíz: " + name
                        );
                    } else if (name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                        throw new ImportValidationException(
                                "El ZIP contiene más de un workbook XLSX."
                        );
                    }

                    Path target = temporary.resolve(name).normalize();
                    if (!target.startsWith(temporary)) {
                        throw new ImportValidationException("El ZIP contiene una ruta insegura.");
                    }
                    Files.createDirectories(target.getParent());
                    long entryBytes = 0;
                    try (OutputStream output = Files.newOutputStream(
                            target,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE
                    )) {
                        byte[] buffer = new byte[16_384];
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            entryBytes += read;
                            extracted += read;
                            if (entryBytes > MAX_ARCHIVE_ENTRY_BYTES
                                    || extracted > MAX_EXTRACTED_BYTES) {
                                throw new ImportValidationException(
                                        "El contenido extraído del ZIP supera el límite seguro."
                                );
                            }
                            output.write(buffer, 0, read);
                        }
                    }
                    zip.closeEntry();
                }
            }
            if (workbooks != 1) {
                throw new ImportValidationException(
                        "El ZIP debe contener un único workbook raíz ringlog-export.xlsx."
                );
            }
            Path workbook = temporary.resolve(NATIVE_WORKBOOK_NAME);
            if (!Files.isRegularFile(workbook, LinkOption.NOFOLLOW_LINKS)) {
                throw new ImportValidationException("No se encontró el workbook del export nativo.");
            }
            if (Files.size(workbook) > MAX_WORKBOOK_BYTES) {
                throw new ImportValidationException("El workbook supera el tamaño máximo seguro.");
            }
            return new ImportSource(archive, workbook, temporary, temporary, true);
        } catch (ImportValidationException exception) {
            cleanupAfterFailure(temporary, exception);
            throw exception;
        } catch (ZipException exception) {
            ImportValidationException validation = new ImportValidationException(
                    "El archivo ZIP está dañado o no es compatible.", exception
            );
            cleanupAfterFailure(temporary, validation);
            throw validation;
        } catch (IOException | RuntimeException exception) {
            ImportValidationException validation = new ImportValidationException(
                    "No se pudo abrir el paquete de importación de forma segura.", exception
            );
            cleanupAfterFailure(temporary, validation);
            throw validation;
        }
    }

    private static void validateInputFile(Path path) throws ImportValidationException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(path)) {
            throw new ImportValidationException("El origen no es un archivo regular legible.");
        }
        if (fileSize(path) <= 0) {
            throw new ImportValidationException("El archivo de importación está vacío.");
        }
    }

    private static long fileSize(Path path) throws ImportValidationException {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new ImportValidationException("No se pudo leer el tamaño del archivo.", exception);
        }
    }

    private static String validateArchiveEntryName(String value)
            throws ImportValidationException {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw new ImportValidationException("El ZIP contiene una ruta vacía o inválida.");
        }
        if (value.indexOf('\\') >= 0) {
            throw new ImportValidationException("El ZIP contiene una ruta con separadores inseguros.");
        }
        return validatePortablePath(value, "entrada ZIP");
    }

    private static String validatePortablePath(String value, String description)
            throws ImportValidationException {
        if (value.startsWith("/") || value.startsWith("\\")
                || value.matches("^[A-Za-z]:.*")) {
            throw new ImportValidationException("La ruta de " + description + " es absoluta.");
        }
        String[] segments = value.replace('\\', '/').split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            boolean finalDirectoryMarker = index == segments.length - 1
                    && segment.isEmpty() && value.endsWith("/");
            if ((!finalDirectoryMarker && segment.isEmpty())
                    || ".".equals(segment) || "..".equals(segment)
                    || segment.indexOf(':') >= 0) {
                throw new ImportValidationException("La ruta de " + description + " es insegura.");
            }
        }
        return String.join("/", segments);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("La fuente de importación ya está cerrada.");
        }
    }

    private static void cleanupAfterFailure(Path root, Exception original) {
        if (root == null) {
            return;
        }
        try {
            deleteTree(root);
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
