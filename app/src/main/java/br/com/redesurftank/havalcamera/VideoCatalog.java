package br.com.redesurftank.havalcamera;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Crash-safe metadata and file layout for local automatic and manual trip recordings. */
public final class VideoCatalog {
    private static VideoCatalog shared;
    private static final Pattern NUMERIC_ID = Pattern.compile("[0-9]+");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern MIME = Pattern.compile("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+");
    private static final String PENDING_SEGMENT = "segment.pending.json";
    private static final Pattern TRIP_JSON = Pattern.compile(
            "\\{\\\"tripId\\\":([0-9]+),\\\"source\\\":\\\"(automatic|manual)\\\"," +
            "\\\"startMs\\\":([0-9]+),\\\"endMs\\\":(null|[0-9]+)," +
            "\\\"durationMs\\\":([0-9]+),\\\"byteSize\\\":([0-9]+)," +
            "\\\"completed\\\":(true|false),\\\"segments\\\":\\[(.*)\\]\\}", Pattern.DOTALL);
    private static final Pattern SEGMENT_JSON = Pattern.compile(
            "\\{\\\"segmentId\\\":([0-9]+),\\\"fileName\\\":\\\"([0-9]{6}\\.mp4)\\\"," +
            "\\\"startMs\\\":([0-9]+),\\\"endMs\\\":([0-9]+)," +
            "\\\"durationUs\\\":([0-9]+),\\\"byteSize\\\":([0-9]+)," +
            "\\\"sha256\\\":\\\"([0-9a-f]{64})\\\",\\\"codec\\\":\\{" +
            "\\\"mime\\\":\\\"([A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+)\\\"," +
            "\\\"width\\\":([0-9]+),\\\"height\\\":([0-9]+)," +
            "\\\"frameRate\\\":([0-9]+),\\\"bitrate\\\":([0-9]+)\\}\\}");

    private final File root;
    private final Map<Long, MutableTrip> trips = new LinkedHashMap<>();
    private final Map<Long, Integer> readLeases = new LinkedHashMap<>();

    public VideoCatalog(File filesDirectory) throws IOException {
        if (filesDirectory == null) throw new NullPointerException("filesDirectory");
        File canonicalFiles = filesDirectory.getCanonicalFile();
        root = new File(canonicalFiles, "trip-video").getCanonicalFile();
        if (!canonicalFiles.equals(root.getParentFile())) throw new IOException("Unsafe catalog root");
        Files.createDirectories(root.toPath());
    }

    /** One process-wide owner prevents recovery from racing an active muxer. */
    public static synchronized VideoCatalog shared(File filesDirectory) throws IOException {
        if (shared == null) {
            shared = new VideoCatalog(filesDirectory);
            shared.recover();
        }
        return shared;
    }

    public synchronized void beginTrip(String tripId, long startMs, Source source) throws IOException {
        long id = parseTripId(tripId);
        if (startMs < 0 || source == null) throw new IllegalArgumentException("Invalid trip metadata");
        MutableTrip existing = trips.get(id);
        if (existing != null) {
            if (existing.startMs == startMs && existing.source == source) {
                if (existing.completed) {
                    if (readLeases.getOrDefault(id, 0) > 0) {
                        throw new IllegalStateException("Trip is being read");
                    }
                    Long completedEndMs = existing.endMs;
                    existing.completed = false;
                    existing.endMs = null;
                    try {
                        writeTrip(existing);
                    } catch (IOException failure) {
                        existing.completed = true;
                        existing.endMs = completedEndMs;
                        throw failure;
                    }
                }
                writeIndex();
                return;
            }
            throw new IllegalStateException("Trip already exists: " + id);
        }
        File directory = tripDirectory(id);
        Files.createDirectory(directory.toPath());
        MutableTrip trip = new MutableTrip(id, source, startMs);
        trips.put(id, trip);
        try {
            writeTrip(trip);
        } catch (IOException failure) {
            trips.remove(id);
            throw failure;
        }
        writeIndex();
    }

    public synchronized long tripStartMs(String tripId) {
        return requireTrip(parseTripId(tripId)).startMs;
    }

    public synchronized Segment completeSegment(String tripId, SegmentMetadata metadata)
            throws IOException {
        MutableTrip trip = requireTrip(parseTripId(tripId));
        if (trip.completed) throw new IllegalStateException("Trip is already complete");
        if (metadata == null) throw new NullPointerException("metadata");

        File directory = tripDirectory(trip.tripId);
        File partial = fixedChild(directory, "current.partial");
        if (!partial.isFile()) throw new IOException("Missing current.partial for trip " + trip.tripId);
        if (partial.length() != metadata.byteSize) throw new IOException("Segment byte size mismatch");

        int segmentId = trip.segments.size() + 1;
        if (segmentId > 999_999) throw new IOException("Too many segments");
        String fileName = String.format(Locale.US, "%06d.mp4", segmentId);
        File completedFile = fixedChild(directory, fileName);
        if (completedFile.exists()) throw new IOException("Completed segment already exists: " + fileName);
        Segment segment = new Segment(segmentId, fileName, metadata);
        File pending = fixedChild(directory, PENDING_SEGMENT);
        writeAtomically(pending, segmentJson(segment));
        try {
            atomicMove(partial.toPath(), completedFile.toPath());
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(pending.toPath());
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }

        trip.segments.add(segment);
        try {
            writeTrip(trip);
        } catch (IOException failure) {
            trip.segments.remove(trip.segments.size() - 1);
            throw failure;
        }
        writeIndex();
        Files.delete(pending.toPath());
        return segment;
    }

    public synchronized void completeTrip(String tripId, long endMs) throws IOException {
        MutableTrip trip = requireTrip(parseTripId(tripId));
        if (endMs < trip.startMs) throw new IllegalArgumentException("Trip end precedes start");
        if (trip.completed) {
            if (trip.endMs == endMs) {
                writeIndex();
                return;
            }
            throw new IllegalStateException("Trip is already complete");
        }
        trip.completed = true;
        trip.endMs = endMs;
        try {
            writeTrip(trip);
        } catch (IOException failure) {
            trip.completed = false;
            trip.endMs = null;
            throw failure;
        }
        writeIndex();
    }

    public synchronized void discardTrip(String tripId) throws IOException {
        long id = parseTripId(tripId);
        if (readLeases.getOrDefault(id, 0) > 0) throw new IllegalStateException("Trip is being read");
        File directory = tripDirectory(id);
        if (directory.exists()) deleteTree(directory.toPath());
        trips.remove(id);
        writeIndex();
    }

    public synchronized void recover() throws IOException {
        Map<Long, MutableTrip> recovered = new LinkedHashMap<>();
        try (Stream<Path> children = Files.list(root.toPath())) {
            for (Path child : (Iterable<Path>) children::iterator) {
                BasicFileAttributes attributes = Files.readAttributes(
                        child, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                String name = child.getFileName().toString();
                if (!attributes.isDirectory() || !NUMERIC_ID.matcher(name).matches()) continue;
                long id = parseTripId(name);
                File directory = tripDirectory(id);
                File metadata = fixedChild(directory, "trip.json");
                if (!metadata.isFile()) continue;
                MutableTrip trip = parseTrip(new String(
                        Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8));
                if (trip.tripId != id) throw new IOException("Trip metadata ID does not match directory");
                recoverPendingSegment(directory, trip);
                Files.deleteIfExists(fixedChild(directory, "current.partial").toPath());
                Files.deleteIfExists(fixedChild(directory, PENDING_SEGMENT + ".tmp").toPath());
                for (Segment segment : trip.segments) {
                    if (!fixedChild(directory, segment.fileName).isFile()) {
                        throw new IOException("Missing completed segment " + segment.fileName);
                    }
                }
                recovered.put(id, trip);
            }
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid trip metadata", malformed);
        }
        trips.clear();
        trips.putAll(recovered);
        writeIndex();
    }

    private void recoverPendingSegment(File directory, MutableTrip trip) throws IOException {
        File pendingFile = fixedChild(directory, PENDING_SEGMENT);
        if (!pendingFile.exists()) return;
        if (!pendingFile.isFile()) throw new IOException("Pending segment metadata is not a file");
        Segment pending = parseSegment(new String(
                Files.readAllBytes(pendingFile.toPath()), StandardCharsets.UTF_8));
        File completedFile = fixedChild(directory, pending.fileName);
        File partial = fixedChild(directory, "current.partial");
        if (!completedFile.exists()) {
            if (!partial.isFile()) {
                Files.delete(pendingFile.toPath());
                return;
            }
            if (partial.length() != pending.byteSize) {
                throw new IOException("Pending segment byte size mismatch");
            }
            atomicMove(partial.toPath(), completedFile.toPath());
        } else if (partial.exists()) {
            throw new IOException("Pending segment has both partial and completed files");
        }
        if (!completedFile.isFile() || completedFile.length() != pending.byteSize) {
            throw new IOException("Pending completed segment is invalid");
        }

        if (pending.segmentId == trip.segments.size() + 1) {
            trip.segments.add(pending);
            try {
                writeTrip(trip);
            } catch (IOException failure) {
                trip.segments.remove(trip.segments.size() - 1);
                throw failure;
            }
        } else if (pending.segmentId > trip.segments.size()
                || !segmentJson(trip.segments.get(pending.segmentId - 1))
                        .equals(segmentJson(pending))) {
            throw new IOException("Pending segment does not match trip metadata");
        }
        Files.delete(pendingFile.toPath());
    }

    public synchronized List<Trip> localSnapshot() {
        return snapshot(false);
    }

    public synchronized List<Trip> completedSnapshot() {
        return snapshot(true);
    }

    /** Resolves only a completed catalog segment; callers never supply a filesystem path. */
    public synchronized File completedSegmentFile(String tripId, String segmentId) throws IOException {
        long id = parseTripId(tripId);
        int ordinal;
        try { ordinal = Integer.parseInt(segmentId); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid segment ID", invalid); }
        MutableTrip trip = requireTrip(id);
        if (!trip.completed || ordinal < 1 || ordinal > trip.segments.size()) {
            throw new IllegalArgumentException("Completed segment not found");
        }
        return fixedChild(tripDirectory(id), trip.segments.get(ordinal - 1).fileName);
    }

    public synchronized ReadLease acquireReadLease(String tripId) {
        long id = parseTripId(tripId);
        MutableTrip trip = requireTrip(id);
        if (!trip.completed) throw new IllegalArgumentException("Completed trip required");
        readLeases.put(id, readLeases.getOrDefault(id, 0) + 1);
        return new ReadLease(this, id);
    }

    public synchronized Set<Long> leasedTripIds() { return Collections.unmodifiableSet(new HashSet<>(readLeases.keySet())); }

    private synchronized void releaseReadLease(long tripId) {
        int count = readLeases.getOrDefault(tripId, 0);
        if (count <= 1) readLeases.remove(tripId); else readLeases.put(tripId, count - 1);
    }

    public static final class ReadLease implements AutoCloseable {
        private VideoCatalog catalog; private final long tripId;
        private ReadLease(VideoCatalog catalog, long tripId) { this.catalog = catalog; this.tripId = tripId; }
        @Override public synchronized void close() {
            if (catalog == null) return;
            catalog.releaseReadLease(tripId); catalog = null;
        }
    }

    private List<Trip> snapshot(boolean completedOnly) {
        List<Trip> snapshot = new ArrayList<>();
        for (MutableTrip trip : trips.values()) {
            if (!completedOnly || trip.completed) snapshot.add(trip.snapshot());
        }
        snapshot.sort(Comparator.comparingLong((Trip trip) -> trip.startMs)
                .thenComparingLong(trip -> trip.tripId));
        return Collections.unmodifiableList(snapshot);
    }

    private void writeTrip(MutableTrip trip) throws IOException {
        writeAtomically(fixedChild(tripDirectory(trip.tripId), "trip.json"), tripJson(trip));
    }

    private void writeIndex() throws IOException {
        StringBuilder json = new StringBuilder("{\"trips\":[");
        boolean first = true;
        for (Trip trip : localSnapshot()) {
            if (!first) json.append(',');
            json.append(tripJson(trip));
            first = false;
        }
        json.append("]}");
        writeAtomically(fixedChild(root, "index.json"), json.toString());
    }

    private void writeAtomically(File destination, String contents) throws IOException {
        File temporary = fixedChild(destination.getParentFile(), destination.getName() + ".tmp");
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.getFD().sync();
        }
        atomicMove(temporary.toPath(), destination.toPath());
    }

    private static void atomicMove(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Recording filesystem does not support atomic rename", unsupported);
        }
    }

    private MutableTrip requireTrip(long tripId) {
        MutableTrip trip = trips.get(tripId);
        if (trip == null) throw new IllegalStateException("Unknown trip: " + tripId);
        return trip;
    }

    private File tripDirectory(long tripId) throws IOException {
        File directory = new File(root, Long.toString(tripId)).getCanonicalFile();
        if (!root.equals(directory.getParentFile())) throw new IOException("Unsafe trip path");
        return directory;
    }

    private static File fixedChild(File parent, String name) throws IOException {
        File child = new File(parent, name).getCanonicalFile();
        if (!parent.getCanonicalFile().equals(child.getParentFile())) throw new IOException("Unsafe file path");
        return child;
    }

    private static long parseTripId(String value) {
        if (value == null || !NUMERIC_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid numeric trip ID");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException tooLarge) {
            throw new IllegalArgumentException("Invalid numeric trip ID", tooLarge);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            Path[] ordered = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            for (Path path : ordered) Files.delete(path);
        }
    }

    private static String tripJson(MutableTrip trip) {
        return tripJson(trip.snapshot());
    }

    private static String tripJson(Trip trip) {
        long byteSize = 0;
        for (Segment segment : trip.segments) byteSize = Math.addExact(byteSize, segment.byteSize);
        long durationMs = trip.endMs == null ? 0 : trip.endMs - trip.startMs;
        StringBuilder json = new StringBuilder();
        json.append("{\"tripId\":").append(trip.tripId)
                .append(",\"source\":\"").append(trip.source.value).append('\"')
                .append(",\"startMs\":").append(trip.startMs)
                .append(",\"endMs\":").append(trip.endMs == null ? "null" : trip.endMs)
                .append(",\"durationMs\":").append(durationMs)
                .append(",\"byteSize\":").append(byteSize)
                .append(",\"completed\":").append(trip.completed)
                .append(",\"segments\":[");
        for (int index = 0; index < trip.segments.size(); index++) {
            if (index > 0) json.append(',');
            json.append(segmentJson(trip.segments.get(index)));
        }
        return json.append("]}").toString();
    }

    private static String segmentJson(Segment segment) {
        return new StringBuilder("{\"segmentId\":").append(segment.segmentId)
                .append(",\"fileName\":\"").append(segment.fileName).append('\"')
                .append(",\"startMs\":").append(segment.startMs)
                .append(",\"endMs\":").append(segment.endMs)
                .append(",\"durationUs\":").append(segment.durationUs)
                .append(",\"byteSize\":").append(segment.byteSize)
                .append(",\"sha256\":\"").append(segment.sha256).append('\"')
                .append(",\"codec\":{\"mime\":\"").append(segment.codec.mime).append('\"')
                .append(",\"width\":").append(segment.codec.width)
                .append(",\"height\":").append(segment.codec.height)
                .append(",\"frameRate\":").append(segment.codec.frameRate)
                .append(",\"bitrate\":").append(segment.codec.bitrate).append("}}")
                .toString();
    }

    private static Segment parseSegment(String json) {
        Matcher matcher = SEGMENT_JSON.matcher(json);
        if (!matcher.matches()) throw new IllegalArgumentException("Malformed pending segment");
        return segmentFromMatcher(matcher);
    }

    private static Segment segmentFromMatcher(Matcher matcher) {
        Codec codec = new Codec(matcher.group(8),
                Integer.parseInt(matcher.group(9)),
                Integer.parseInt(matcher.group(10)),
                Integer.parseInt(matcher.group(11)),
                Integer.parseInt(matcher.group(12)));
        SegmentMetadata metadata = new SegmentMetadata(
                Long.parseLong(matcher.group(3)),
                Long.parseLong(matcher.group(4)),
                Long.parseLong(matcher.group(5)),
                Long.parseLong(matcher.group(6)),
                matcher.group(7), codec);
        int segmentId = Integer.parseInt(matcher.group(1));
        if (segmentId < 1) throw new IllegalArgumentException("Invalid segment ID");
        String expectedName = String.format(Locale.US, "%06d.mp4", segmentId);
        if (!expectedName.equals(matcher.group(2))) {
            throw new IllegalArgumentException("Invalid segment file name");
        }
        return new Segment(segmentId, expectedName, metadata);
    }

    private static MutableTrip parseTrip(String json) {
        Matcher tripMatcher = TRIP_JSON.matcher(json);
        if (!tripMatcher.matches()) throw new IllegalArgumentException("Malformed trip JSON");
        long tripId = Long.parseLong(tripMatcher.group(1));
        MutableTrip trip = new MutableTrip(tripId, Source.fromValue(tripMatcher.group(2)),
                Long.parseLong(tripMatcher.group(3)));
        trip.endMs = "null".equals(tripMatcher.group(4))
                ? null : Long.parseLong(tripMatcher.group(4));
        trip.completed = Boolean.parseBoolean(tripMatcher.group(7));

        String segmentsJson = tripMatcher.group(8);
        Matcher segmentMatcher = SEGMENT_JSON.matcher(segmentsJson);
        int cursor = 0;
        while (segmentMatcher.find()) {
            if (segmentMatcher.start() != cursor) {
                if (cursor == 0 || segmentMatcher.start() != cursor + 1
                        || segmentsJson.charAt(cursor) != ',') {
                    throw new IllegalArgumentException("Malformed segment list");
                }
            }
            Segment segment = segmentFromMatcher(segmentMatcher);
            if (segment.segmentId != trip.segments.size() + 1) {
                throw new IllegalArgumentException("Segments are not ordered");
            }
            trip.segments.add(segment);
            cursor = segmentMatcher.end();
        }
        if (cursor != segmentsJson.length()) throw new IllegalArgumentException("Malformed segment list");
        if (trip.completed != (trip.endMs != null)) {
            throw new IllegalArgumentException("Invalid trip completion state");
        }
        return trip;
    }

    public enum Source {
        AUTOMATIC("automatic"), MANUAL("manual");

        final String value;

        Source(String value) {
            this.value = value;
        }

        static Source fromValue(String value) {
            for (Source source : values()) if (source.value.equals(value)) return source;
            throw new IllegalArgumentException("Unknown recording source");
        }
    }

    public static final class Codec {
        public final String mime;
        public final int width;
        public final int height;
        public final int frameRate;
        public final int bitrate;

        public Codec(String mime, int width, int height, int frameRate, int bitrate) {
            if (mime == null || !MIME.matcher(mime).matches()
                    || width <= 0 || height <= 0 || frameRate <= 0 || bitrate <= 0) {
                throw new IllegalArgumentException("Invalid codec metadata");
            }
            this.mime = mime;
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
            this.bitrate = bitrate;
        }
    }

    public static final class SegmentMetadata {
        public final long startMs;
        public final long endMs;
        public final long durationUs;
        public final long byteSize;
        public final String sha256;
        public final Codec codec;

        public SegmentMetadata(long startMs, long endMs, long durationUs,
                long byteSize, String sha256, Codec codec) {
            if (startMs < 0 || endMs < startMs || durationUs < 0 || byteSize < 0
                    || sha256 == null || !SHA_256.matcher(sha256).matches() || codec == null) {
                throw new IllegalArgumentException("Invalid segment metadata");
            }
            this.startMs = startMs;
            this.endMs = endMs;
            this.durationUs = durationUs;
            this.byteSize = byteSize;
            this.sha256 = sha256.toLowerCase(Locale.US);
            this.codec = codec;
        }
    }

    public static final class Segment {
        public final int segmentId;
        public final String fileName;
        public final long startMs;
        public final long endMs;
        public final long durationUs;
        public final long byteSize;
        public final String sha256;
        public final Codec codec;

        private Segment(int segmentId, String fileName, SegmentMetadata metadata) {
            this.segmentId = segmentId;
            this.fileName = fileName;
            this.startMs = metadata.startMs;
            this.endMs = metadata.endMs;
            this.durationUs = metadata.durationUs;
            this.byteSize = metadata.byteSize;
            this.sha256 = metadata.sha256;
            this.codec = metadata.codec;
        }
    }

    public static final class Trip {
        public final long tripId;
        public final Source source;
        public final long startMs;
        public final Long endMs;
        public final boolean completed;
        public final List<Segment> segments;

        private Trip(long tripId, Source source, long startMs, Long endMs,
                boolean completed, List<Segment> segments) {
            this.tripId = tripId;
            this.source = source;
            this.startMs = startMs;
            this.endMs = endMs;
            this.completed = completed;
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        }
    }

    private static final class MutableTrip {
        final long tripId;
        final Source source;
        final long startMs;
        final List<Segment> segments = new ArrayList<>();
        Long endMs;
        boolean completed;

        MutableTrip(long tripId, Source source, long startMs) {
            this.tripId = tripId;
            this.source = source;
            this.startMs = startMs;
        }

        Trip snapshot() {
            return new Trip(tripId, source, startMs, endMs, completed, segments);
        }
    }
}
