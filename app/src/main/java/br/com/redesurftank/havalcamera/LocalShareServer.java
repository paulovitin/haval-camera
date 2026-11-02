package br.com.redesurftank.havalcamera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ten-minute, one-client, read-only LAN portal for completed catalog files. */
public final class LocalShareServer implements AutoCloseable {
    static final int MAX_REQUEST_BYTES = 16 * 1024;
    static final long LIFETIME_MS = 10 * 60_000;
    static final long MAX_RANGE_BYTES = 8 * 1024 * 1024;
    private final ServerSocket socket;
    private final Session session;
    private final Thread worker;

    public LocalShareServer(InetAddress wifiAddress, File filesDirectory, VideoCatalog catalog)
            throws IOException {
        if (wifiAddress == null || !isPrivateWifiAddress(wifiAddress.getHostAddress()))
            throw new IllegalArgumentException("Active private Wi-Fi address required");
        SecureRandom random = new SecureRandom();
        String code = String.format(Locale.US, "%06d", random.nextInt(1_000_000));
        session = new Session(filesDirectory, catalog, code, System.currentTimeMillis(), bytes -> {
            byte[] token = new byte[16]; random.nextBytes(token); return hex(token);
        });
        socket = new ServerSocket();
        socket.setReuseAddress(false);
        socket.bind(new InetSocketAddress(wifiAddress, 0), 1);
        worker = new Thread(this::acceptLoop, "CameraLocalShare");
        worker.start();
    }

    public String pairingCode() { return session.code; }
    public int port() { return socket.getLocalPort(); }
    public long expiresAtMs() { return session.expiresAtMs; }

    private void acceptLoop() {
        while (!session.isClosed()) {
            try (Socket client = socket.accept()) {
                client.setSoTimeout(5_000);
                Response response = handle(readBounded(client.getInputStream()),
                        client.getInetAddress().getHostAddress(), System.currentTimeMillis());
                write(client.getOutputStream(), response);
            } catch (IOException failure) {
                if (!session.isClosed()) close();
            }
        }
    }

    private Response handle(byte[] raw, String client, long now) {
        ParsedRequest request = parseRequest(raw);
        if (request.error != null) return request.error;
        if (!"GET".equals(request.method)) return Response.text(405, "Read only");
        if (request.path.startsWith("/pair?code="))
            return session.pair(client, request.path.substring(11), now);
        String cookie = request.cookie;
        if ("/".equals(request.path)) return session.list(client, cookie, now);
        Matcher matcher = Pattern.compile("/trip/([0-9]+)/segment/([0-9]+)").matcher(request.path);
        if (matcher.matches()) return session.download(client, cookie, matcher.group(1),
                matcher.group(2), request.range, now);
        return Response.text(404, "Not found");
    }

    static ParsedRequest parseRequest(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > MAX_REQUEST_BYTES)
            return ParsedRequest.error(Response.text(400, "Invalid request"));
        String text = new String(raw, StandardCharsets.US_ASCII);
        int end = text.indexOf("\r\n\r\n");
        if (end < 0) return ParsedRequest.error(Response.text(400, "Invalid request"));
        String[] lines = text.substring(0, end).split("\r\n", -1);
        if (lines.length > 64) return ParsedRequest.error(Response.text(431, "Headers too large"));
        String[] first = lines[0].split(" ");
        if (first.length != 3 || !"HTTP/1.1".equals(first[2]) || first[1].length() > 512)
            return ParsedRequest.error(Response.text(400, "Invalid request"));
        String cookie = null, range = null;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].length() > 1024) return ParsedRequest.error(Response.text(431, "Headers too large"));
            int colon = lines[i].indexOf(':');
            if (colon <= 0) return ParsedRequest.error(Response.text(400, "Invalid header"));
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.US);
            String value = lines[i].substring(colon + 1).trim();
            if ("cookie".equals(name) && value.startsWith("haval_camera=")) cookie = value.substring(13);
            if ("range".equals(name)) range = value;
        }
        return new ParsedRequest(first[0], first[1], cookie, range, null);
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() <= MAX_REQUEST_BYTES) {
            int value = input.read();
            if (value < 0) break;
            bytes.write(value);
            byte[] end = {'\r','\n','\r','\n'};
            matched = value == end[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
            if (matched == 4) return bytes.toByteArray();
        }
        return bytes.toByteArray();
    }

    private static void write(OutputStream output, Response response) throws IOException {
        String headers = "HTTP/1.1 " + response.status + " " + reason(response.status)
                + "\r\nContent-Length: " + response.body.length
                + "\r\nContent-Type: " + response.contentType
                + (response.cookie == null ? "" : "\r\nSet-Cookie: haval_camera=" + response.cookie
                    + "; HttpOnly; SameSite=Strict")
                + (response.contentRange == null ? "" : "\r\nContent-Range: " + response.contentRange)
                + "\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(response.body);
        output.flush();
    }

    private static String reason(int status) {
        switch (status) { case 200: return "OK"; case 206: return "Partial Content";
            case 400: return "Bad Request"; case 401: return "Unauthorized";
            case 403: return "Forbidden"; case 404: return "Not Found";
            case 410: return "Gone"; case 416: return "Range Not Satisfiable";
            case 423: return "Locked"; case 431: return "Request Header Fields Too Large";
            default: return "Method Not Allowed"; }
    }

    public static boolean isPrivateWifiAddress(String address) {
        if (address == null) return false;
        if (address.startsWith("10.") || address.startsWith("192.168.")) return true;
        Matcher matcher = Pattern.compile("172\\.([0-9]{1,3})\\..*").matcher(address);
        if (matcher.matches()) { int second = Integer.parseInt(matcher.group(1)); return second >= 16 && second <= 31; }
        return address.toLowerCase(Locale.US).startsWith("fd") || address.toLowerCase(Locale.US).startsWith("fc");
    }

    @Override public void close() {
        session.close();
        try { socket.close(); } catch (IOException ignored) { }
    }

    interface TokenSource { String create(int bytes); }

    public static final class Session implements AutoCloseable {
        private final File filesDirectory;
        private final VideoCatalog catalog;
        final String code;
        final long expiresAtMs;
        private final TokenSource tokens;
        private int failures;
        private String cookie;
        private String client;
        private boolean closed;

        Session(File filesDirectory, VideoCatalog catalog, String code, long now, TokenSource tokens)
                throws IOException {
            this.filesDirectory = filesDirectory.getCanonicalFile();
            this.catalog = catalog;
            if (code == null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("Pairing code");
            this.code = code; this.expiresAtMs = now + LIFETIME_MS; this.tokens = tokens;
        }
        synchronized Response pair(String address, String supplied, long now) {
            Response unavailable = unavailable(now); if (unavailable != null) return unavailable;
            if (failures >= 5) return Response.text(423, "Locked");
            if (!code.equals(supplied)) { failures++; return Response.text(failures >= 5 ? 423 : 401, "Pairing failed"); }
            if (client != null && !client.equals(address)) return Response.text(403, "One client only");
            client = address; cookie = tokens.create(16);
            if (cookie == null || !cookie.matches("[0-9a-f]{32}")) throw new IllegalStateException("Invalid token source");
            return new Response(200, "Paired".getBytes(StandardCharsets.UTF_8), "text/plain", cookie, null);
        }
        synchronized Response list(String address, String suppliedCookie, long now) {
            Response auth = authorize(address, suppliedCookie, now); if (auth != null) return auth;
            StringBuilder html = new StringBuilder("<!doctype html><meta charset=utf-8><title>Haval Camera</title><ul>");
            for (VideoCatalog.Trip trip : catalog.completedSnapshot()) for (VideoCatalog.Segment segment : trip.segments)
                html.append("<li><a href=\"/trip/").append(trip.tripId).append("/segment/")
                        .append(segment.segmentId).append("\">Viagem ").append(trip.tripId)
                        .append(" - segmento ").append(segment.segmentId).append("</a></li>");
            return new Response(200, html.append("</ul>").toString().getBytes(StandardCharsets.UTF_8),
                    "text/html; charset=utf-8", null, null);
        }
        synchronized Response download(String address, String suppliedCookie, String tripId,
                String segmentId, String range, long now) {
            Response auth = authorize(address, suppliedCookie, now); if (auth != null) return auth;
            if (!tripId.matches("[0-9]+") || !segmentId.matches("[0-9]+")) return Response.text(400, "Invalid ID");
            try {
                File file = catalog.completedSegmentFile(tripId, segmentId);
                File canonical = file.getCanonicalFile();
                if (!canonical.isFile() || !canonical.getPath().startsWith(filesDirectory.getPath() + File.separator))
                    return Response.text(404, "Not found");
                if (range == null && canonical.length() > MAX_RANGE_BYTES)
                    return Response.text(416, "Range required");
                long start = 0, end = canonical.length() - 1;
                if (range != null) {
                    Matcher match = Pattern.compile("bytes=([0-9]+)-([0-9]*)").matcher(range);
                    if (!match.matches()) return Response.text(416, "Invalid range");
                    start = Long.parseLong(match.group(1));
                    end = match.group(2).isEmpty() ? Math.min(canonical.length() - 1, start + MAX_RANGE_BYTES - 1)
                            : Long.parseLong(match.group(2));
                }
                if (start < 0 || end < start || start >= canonical.length()
                        || end >= canonical.length() || end - start + 1 > MAX_RANGE_BYTES)
                    return Response.text(416, "Invalid range");
                int length = Math.toIntExact(end - start + 1);
                byte[] body = new byte[length];
                try (FileInputStream input = new FileInputStream(canonical)) {
                    long skipped = input.skip(start);
                    if (skipped != start) return Response.text(404, "Not found");
                    int offset = 0;
                    while (offset < length) { int count = input.read(body, offset, length - offset); if (count < 0) break; offset += count; }
                    if (offset != length) return Response.text(404, "Not found");
                }
                return new Response(range == null ? 200 : 206, body, "video/mp4", null,
                        range == null ? null : "bytes " + start + "-" + end + "/" + canonical.length());
            } catch (IllegalArgumentException | IllegalStateException | IOException invalid) {
                return Response.text(404, "Not found");
            }
        }
        private Response authorize(String address, String supplied, long now) {
            Response unavailable = unavailable(now); if (unavailable != null) return unavailable;
            if (cookie == null || !cookie.equals(supplied)) return Response.text(401, "Pair first");
            return client.equals(address) ? null : Response.text(403, "Wrong client");
        }
        private Response unavailable(long now) {
            if (closed || now >= expiresAtMs) { close(); return Response.text(410, "Sharing ended"); }
            return null;
        }
        public synchronized boolean isClosed() { return closed; }
        @Override public synchronized void close() { closed = true; cookie = null; client = null; }
    }

    static final class ParsedRequest {
        final String method, path, cookie, range; final Response error;
        ParsedRequest(String method, String path, String cookie, String range, Response error) {
            this.method=method; this.path=path; this.cookie=cookie; this.range=range; this.error=error;
        }
        static ParsedRequest error(Response error) { return new ParsedRequest(null,null,null,null,error); }
    }

    public static final class Response {
        public final int status; public final byte[] body; public final String contentType;
        public final String cookie; public final String contentRange;
        Response(int status, byte[] body, String contentType, String cookie, String contentRange) {
            this.status=status; this.body=body; this.contentType=contentType;
            this.cookie=cookie; this.contentRange=contentRange;
        }
        static Response text(int status, String text) {
            return new Response(status, text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", null, null);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value));
        return result.toString();
    }
}
