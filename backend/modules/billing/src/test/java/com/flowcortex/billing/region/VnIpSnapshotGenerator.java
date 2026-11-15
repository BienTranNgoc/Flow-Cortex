package com.flowcortex.billing.region;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds the committed VN address snapshot from the five registries' delegated files, verifying each
 * {@code .md5}. Run through {@code ./gradlew :modules:billing:generateVnIpSnapshot}; {@code -PfromDir=<dir>}
 * reads files already downloaded under their published names ({@code delegated-apnic-latest} and
 * {@code delegated-apnic-latest.md5}, ...) and {@code -Pout=<file>} overrides the output path.
 */
public final class VnIpSnapshotGenerator {

    private VnIpSnapshotGenerator() { }

    public static void main(String[] args) throws IOException {
        String fromDir = argument(args, "--from-dir");
        Path out = Path.of(argument(args, "--out"));
        System.out.println(run(fromDir == null ? null : Path.of(fromDir), out, Instant.now()));
    }

    /** Writes the snapshot and returns the summary line; throws, leaving {@code out} untouched, below the bounds. */
    static String run(Path fromDir, Path out, Instant now) throws IOException {
        List<RirFetch.Download> downloads = fromDir == null
                ? RirFetch.download(RirFetch.httpFetcher(), RirFetch.RIR_SOURCES)
                : readFromDir(fromDir);
        VnIpTable table = RirFetch.buildTable(downloads, now);
        String rejection = RirFetch.sanityRejection(table, null);
        if (rejection != null) {
            throw new IllegalStateException("refusing to write the snapshot: " + rejection);
        }
        Files.writeString(out, table.serialise());
        return "wrote " + out + ": " + table.ipv4RangeCount() + " IPv4 ranges from " + table.ipv4RecordCount()
                + " records, " + table.ipv6RangeCount() + " IPv6 ranges from " + table.ipv6RecordCount()
                + " records";
    }

    private static List<RirFetch.Download> readFromDir(Path dir) throws IOException {
        List<RirFetch.Download> downloads = new ArrayList<>();
        for (RirFetch.Source source : RirFetch.RIR_SOURCES) {
            Path file = dir.resolve(source.url().substring(source.url().lastIndexOf('/') + 1));
            Path md5 = dir.resolve(file.getFileName() + ".md5");
            downloads.add(new RirFetch.Download(source, Files.readAllBytes(file),
                    Files.exists(md5) ? Files.readString(md5) : null));
        }
        return downloads;
    }

    private static String argument(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        if ("--out".equals(name)) {
            throw new IllegalArgumentException("--out <file> is required");
        }
        return null;
    }
}
