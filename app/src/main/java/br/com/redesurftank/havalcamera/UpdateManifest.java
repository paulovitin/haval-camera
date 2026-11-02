package br.com.redesurftank.havalcamera;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict, JVM-only metadata and GitHub asset selection. No filename guessing. */
public final class UpdateManifest {
    public static final long MAX_APK_BYTES = 1L << 30;
    public static final String MANIFEST_ASSET = "update-manifest.json";
    public final int versionCode;
    public final String versionName, apk, sha256;
    public final long size;

    UpdateManifest(int code, String name, String apk, long size, String hash) {
        this.versionCode = code; this.versionName = name; this.apk = apk; this.size = size; this.sha256 = hash;
    }
    public static UpdateManifest parse(String json) {
        Map<String,Object> value = object(Json.parse(json));
        require(value.keySet().equals(new HashSet<>(Arrays.asList("schemaVersion", "versionCode", "versionName", "apk", "size", "sha256"))));
        require(number(value.get("schemaVersion")) == 1);
        long code = number(value.get("versionCode")), size = number(value.get("size"));
        String name = string(value.get("versionName")), apk = string(value.get("apk")), hash = string(value.get("sha256"));
        require(code > 0 && code <= Integer.MAX_VALUE && size > 0 && size <= MAX_APK_BYTES);
        require(!name.trim().isEmpty() && name.length() <= 128 && name.chars().noneMatch(c -> c < 32 || c == 127));
        require(apk.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.apk") && hash.matches("[0-9a-f]{64}"));
        return new UpdateManifest((int) code, name, apk, size, hash);
    }
    public boolean isNewerThan(int installed) { return versionCode > installed; }

    /** /latest is one object; opt-in /releases is newest-first. */
    public static Release selectRelease(String json, boolean includePrerelease) {
        Object parsed = Json.parse(json);
        List<?> releases = parsed instanceof List ? (List<?>) parsed : Arrays.asList(parsed);
        for (Object entry : releases) {
            Map<String,Object> release = object(entry);
            require(release.get("draft") instanceof Boolean && release.get("prerelease") instanceof Boolean);
            if ((Boolean) release.get("draft") || (!includePrerelease && (Boolean) release.get("prerelease"))) continue;
            return new Release(release);
        }
        return null;
    }
    public static final class Release {
        public final String notes;
        private final Map<String,String> assets = new LinkedHashMap<>();
        Release(Map<String,Object> release) {
            Object body = release.get("body");
            String text = body == null ? "" : string(body);
            notes = text.substring(0, Math.min(1500, text.length()));
            require(release.get("assets") instanceof List);
            for (Object item : (List<?>) release.get("assets")) {
                Map<String,Object> asset = object(item);
                String name = string(asset.get("name"));
                require(!assets.containsKey(name));
                assets.put(name, string(asset.get("browser_download_url")));
            }
        }
        public String manifestUrl() { return assetUrl(MANIFEST_ASSET); }
        public String apkUrl(UpdateManifest manifest) { return assetUrl(manifest.apk); }
        private String assetUrl(String name) {
            String url = assets.get(name); require(url != null);
            URI uri = URI.create(url);
            require("https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                    && uri.getPort() == -1 && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null);
            String[] path = uri.getPath().split("/", -1);
            require(path.length == 7 && "rafaelcs28".equals(path[1]) && "haval-camera".equals(path[2])
                    && "releases".equals(path[3]) && "download".equals(path[4]) && !path[5].isEmpty()
                    && !".".equals(path[5]) && !"..".equals(path[5]) && name.equals(path[6]));
            return url;
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        require(value instanceof Map); return (Map<String,Object>) value;
    }
    private static String string(Object value) { require(value instanceof String); return (String) value; }
    private static long number(Object value) { require(value instanceof Long); return (Long) value; }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Metadados de atualização inválidos"); }

    /** Bounded JSON reader: avoids Android's lenient/stubbed org.json and a new dependency. */
    private static final class Json {
        final String text; int at;
        Json(String text) { this.text = text; }
        static Object parse(String text) {
            require(text != null && text.length() <= 2_097_152);
            Json reader = new Json(text); Object result = reader.value(0); reader.space();
            require(reader.at == text.length()); return result;
        }
        void space() { while (at < text.length() && " \r\n\t".indexOf(text.charAt(at)) >= 0) at++; }
        boolean take(char c) { space(); if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        Object value(int depth) {
            require(depth < 32); space(); require(at < text.length()); char c = text.charAt(at);
            if (c == '"') return quoted();
            if (take('{')) {
                Map<String,Object> map = new LinkedHashMap<>(); if (take('}')) return map;
                do { space(); String key = quoted(); require(!map.containsKey(key) && take(':')); map.put(key,value(depth+1)); } while (take(','));
                require(take('}')); return map;
            }
            if (take('[')) {
                List<Object> list = new ArrayList<>(); if (take(']')) return list;
                do { list.add(value(depth+1)); } while (take(',')); require(take(']')); return list;
            }
            for (String literal : new String[]{"true","false","null"}) if (text.startsWith(literal,at)) {
                at += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
            int start = at;
            while (at < text.length() && "-+0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
            String token = text.substring(start,at);
            require(token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"));
            try { return Long.valueOf(token); }
            catch (NumberFormatException e) { return new java.math.BigDecimal(token); }
        }
        String quoted() {
            require(take('"')); StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++); if (c == '"') return out.toString(); require(c >= 32);
                if (c == '\\') {
                    require(at < text.length()); c = text.charAt(at++);
                    switch (c) {
                        case '"': case '\\': case '/': break;
                        case 'b': c='\b'; break; case 'f': c='\f'; break; case 'n': c='\n'; break;
                        case 'r': c='\r'; break; case 't': c='\t'; break;
                        case 'u':
                            require(at+4 <= text.length());
                            String hex = text.substring(at,at+4); require(hex.matches("[0-9a-fA-F]{4}"));
                            c=(char)Integer.parseInt(hex,16); at+=4; break;
                        default: throw new IllegalArgumentException("Escape JSON inválido");
                    }
                }
                out.append(c);
            }
            throw new IllegalArgumentException("String JSON incompleta");
        }
    }
}
