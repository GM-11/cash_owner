package com.protrader.cash_owner.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** File names and directory helpers shared by the journal and the snapshots. */
final class JournalFiles {

    private static final Pattern SEGMENT = Pattern.compile("journal-(\\d{20})\\.log");
    private static final Pattern SNAPSHOT = Pattern.compile("snapshot-(\\d{20})\\.json");

    private JournalFiles() {
    }

    /** A journal file and the entry number it starts with (from its name). */
    record Segment(Path path, long firstEntryNo) {
    }

    /** A snapshot file and the entry number it covers (from its name). */
    record SnapshotName(Path path, long entryNo) {
    }

    static String segmentName(long firstEntryNo) {
        return String.format("journal-%020d.log", firstEntryNo);
    }

    static String snapshotName(long entryNo) {
        return String.format("snapshot-%020d.json", entryNo);
    }

    private static OptionalLong parse(Pattern pattern, Path file) {
        Matcher matcher = pattern.matcher(file.getFileName().toString());
        return matcher.matches() ? OptionalLong.of(Long.parseLong(matcher.group(1))) : OptionalLong.empty();
    }

    /** Journal files in the directory, oldest first. */
    static List<Segment> listSegments(Path dir) throws IOException {
        List<Segment> segments = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(file -> parse(SEGMENT, file)
                    .ifPresent(first -> segments.add(new Segment(file, first))));
        }
        segments.sort(Comparator.comparingLong(Segment::firstEntryNo));
        return segments;
    }

    /** Snapshot files in the directory (temp files are not matched), newest first. */
    static List<SnapshotName> listSnapshots(Path dir) throws IOException {
        List<SnapshotName> snapshots = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(file -> parse(SNAPSHOT, file)
                    .ifPresent(entryNo -> snapshots.add(new SnapshotName(file, entryNo))));
        }
        snapshots.sort(Comparator.comparingLong(SnapshotName::entryNo).reversed());
        return snapshots;
    }

    /** Makes created, renamed and deleted files in the directory durable. */
    static void fsyncDir(Path dir) throws IOException {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
