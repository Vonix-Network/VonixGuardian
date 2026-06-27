package network.vonix.guardian.core.logfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Pure utility for daily-rotated audit log files. Package-private. See
 * SHARED-CONTRACTS.md § 6.
 */
final class LogRotator {

    static final Marker MARKER = MarkerFactory.getMarker("VONIXGUARDIAN_LOGFILE");
    private static final Logger LOG = LoggerFactory.getLogger(LogRotator.class);

    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    static final String PREFIX = "audit-";
    static final String SUFFIX_JSONL = ".log.jsonl";
    static final String SUFFIX_GZ = ".log.jsonl.gz";

    private LogRotator() {}

    /** Build the canonical jsonl filename for a given UTC date. */
    static String fileNameFor(LocalDate date) {
        return PREFIX + DATE_FMT.format(date) + SUFFIX_JSONL;
    }

    /**
     * Gzip any .log.jsonl files older than {@code today}, then prune any rotation
     * artifact (gz or jsonl) older than {@code retentionDays} from {@code today}.
     *
     * @param directory      directory holding the audit files
     * @param today          UTC date considered "current" — files for today are left alone
     * @param gzipRotated    when true, rotated jsonl files get gzipped in-place
     * @param retentionDays  days to retain before pruning; non-positive disables pruning
     */
    static void rotateIfNeeded(Path directory, LocalDate today, boolean gzipRotated, int retentionDays) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> entries = listAuditFiles(directory);
        if (gzipRotated) {
            for (Path p : entries) {
                String name = p.getFileName().toString();
                if (!name.endsWith(SUFFIX_JSONL)) {
                    continue;
                }
                LocalDate d = parseDate(name);
                if (d == null || !d.isBefore(today)) {
                    continue;
                }
                try {
                    gzipInPlace(p);
                } catch (IOException e) {
                    LOG.warn(MARKER, "Failed to gzip rotated audit file {}: {}", p, e.toString());
                }
            }
        }
        if (retentionDays <= 0) {
            return;
        }
        LocalDate cutoff = today.minusDays(retentionDays);
        for (Path p : listAuditFiles(directory)) {
            String name = p.getFileName().toString();
            LocalDate d = parseDate(name);
            if (d == null) {
                continue;
            }
            // Keep today; only prune strictly older than cutoff.
            if (d.isBefore(cutoff)) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.warn(MARKER, "Failed to prune old audit file {}: {}", p, e.toString());
                }
            }
        }
    }

    private static List<Path> listAuditFiles(Path directory) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, p -> {
            String n = p.getFileName().toString();
            return n.startsWith(PREFIX) && (n.endsWith(SUFFIX_JSONL) || n.endsWith(SUFFIX_GZ));
        })) {
            for (Path p : stream) {
                out.add(p);
            }
        } catch (IOException e) {
            LOG.warn(MARKER, "Failed to list audit directory {}: {}", directory, e.toString());
        }
        return out;
    }

    /** Parse the date out of either "audit-YYYY-MM-DD.log.jsonl" or its .gz form. */
    static LocalDate parseDate(String fileName) {
        if (!fileName.startsWith(PREFIX)) {
            return null;
        }
        String rest = fileName.substring(PREFIX.length());
        int dot = rest.indexOf(".log.jsonl");
        if (dot < 0) {
            return null;
        }
        String datePart = rest.substring(0, dot);
        try {
            return LocalDate.parse(datePart, DATE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void gzipInPlace(Path src) throws IOException {
        Path tmp = src.resolveSibling(src.getFileName().toString() + ".gz.tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
            Files.copy(src, out);
        }
        Path gz = src.resolveSibling(src.getFileName().toString() + ".gz");
        Files.move(tmp, gz, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(src);
    }
}
