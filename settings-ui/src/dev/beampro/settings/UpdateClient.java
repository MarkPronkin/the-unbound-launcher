package dev.beampro.settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/** GitHub's published releases, including alpha prereleases; no credentials or tracking. */
final class UpdateClient {
    static final String REPOSITORY = "https://github.com/MarkPronkin/the-unbound-launcher";
    static final String API = "https://api.github.com/repos/MarkPronkin/the-unbound-launcher/releases?per_page=100";
    static final long MAX_APK_SIZE = 2L * 1024 * 1024 * 1024;
    interface Progress { void update(long received, long total) throws IOException; }
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    private final Connections connections;

    UpdateClient() { this(url -> (HttpURLConnection) url.openConnection()); }
    UpdateClient(Connections connections) { this.connections = connections; }

    static final class Release {
        final String tag, notes, url, digest;
        final long size;
        Release(String tag, String notes, String url, String digest, long size) {
            this.tag = tag; this.notes = notes; this.url = url; this.digest = digest; this.size = size;
        }
    }

    Release latest(String installed) throws IOException {
        HttpURLConnection connection = connect(API, true);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 4 * 1024 * 1024) throw new IOException("GitHub's release list is too large. Try again later.");
                output.write(buffer, 0, count);
            }
            try { return select(new JSONArray(output.toString("UTF-8")), installed); }
            catch (JSONException error) { throw new IOException("GitHub returned an invalid release list. Try again later.", error); }
        } finally { connection.disconnect(); }
    }

    static Release select(JSONArray releases, String installed) throws JSONException, IOException {
        ReleaseVersion current = ReleaseVersion.parse(installed);
        if (current == null) throw new IOException("This app version cannot be compared with GitHub releases.");
        Release best = null; ReleaseVersion bestVersion = current;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft") || release.isNull("published_at")) continue;
            String tag = release.optString("tag_name");
            ReleaseVersion version = ReleaseVersion.parse(tag);
            if (version == null || version.compareTo(bestVersion) <= 0
                    || (!current.prerelease() && (version.prerelease() || release.optBoolean("prerelease")))) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            JSONObject apk = null;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                String name = asset.optString("name").toLowerCase(Locale.ROOT);
                if (!name.matches("the-unbound-launcher(?:-[a-z0-9.+-]+)?\\.apk")
                        || name.matches(".*(?:preview|debug|test|x86|armeabi|armv7).*")) continue;
                if (!"uploaded".equals(asset.optString("state")) || !validAssetUrl(asset.optString("browser_download_url"))) continue;
                long size = asset.optLong("size");
                if (size <= 0 || size > MAX_APK_SIZE) continue;
                // The release contract is one universal/arm64 APK, never guess between variants.
                if (apk != null) { apk = null; break; }
                apk = asset;
            }
            if (apk == null) continue;
            String digest = apk.isNull("digest") ? "" : apk.optString("digest");
            if (!digest.isEmpty() && !digest.matches("sha256:[0-9a-fA-F]{64}")) continue;
            best = new Release(tag, release.optString("body"), apk.getString("browser_download_url"), digest, apk.getLong("size"));
            bestVersion = version;
        }
        return best;
    }

    static boolean validAssetUrl(String value) {
        try {
            URL url = new URL(value);
            return secure(url) && "github.com".equals(url.getHost())
                    && url.getPath().startsWith("/MarkPronkin/the-unbound-launcher/releases/download/");
        } catch (Exception error) { return false; }
    }

    private static boolean secure(URL url) {
        return "https".equals(url.getProtocol()) && url.getUserInfo() == null && (url.getPort() == -1 || url.getPort() == 443);
    }

    private HttpURLConnection connect(String value, boolean api) throws IOException {
        URL url = new URL(value);
        for (int redirects = 0; redirects <= 5; redirects++) {
            String host = url.getHost();
            if (!secure(url) || !(api ? "api.github.com".equals(host)
                    : "github.com".equals(host) || "release-assets.githubusercontent.com".equals(host)
                    || "objects.githubusercontent.com".equals(host))) throw new IOException("GitHub returned an unsafe download address.");
            HttpURLConnection connection = connections.open(url);
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "The-Unbound-Launcher/" + AppVersion.NAME.replace(' ', '-'));
            connection.setRequestProperty("Accept", api ? "application/vnd.github+json" : "application/octet-stream");
            if (api) connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            int status;
            try { status = connection.getResponseCode(); }
            catch (IOException error) { connection.disconnect(); throw error; }
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null) throw new IOException("GitHub returned an empty redirect.");
                url = new URL(url, location); continue;
            }
            if (status == 200) return connection;
            connection.disconnect();
            if (status == 403 || status == 429) throw new IOException("GitHub is limiting update requests. Try again later.");
            if (status == 404 && api) throw new IOException("The GitHub releases are not available yet. Try again later.");
            throw new IOException("GitHub request failed (HTTP " + status + "). Try again later.");
        }
        throw new IOException("GitHub returned too many redirects.");
    }

    void download(Release release, File destination, Progress progress) throws Exception {
        if (!validAssetUrl(release.url)) throw new IOException("Invalid GitHub release asset.");
        HttpURLConnection connection = connect(release.url, false);
        boolean complete = false;
        try {
            long length = connection.getContentLengthLong();
            if (length > 0 && length != release.size) throw new IOException("The APK size differs from the GitHub release. Check again.");
            if (destination.getParentFile().getUsableSpace() < release.size + 64L * 1024 * 1024)
                throw new IOException("There is not enough free storage to download this update.");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[128 * 1024]; long received = 0; int count;
                while ((count = input.read(buffer)) != -1) {
                    received += count;
                    if (received > release.size || received > MAX_APK_SIZE) throw new IOException("The update download exceeds its expected size.");
                    progress.update(received, release.size);
                    digest.update(buffer, 0, count); output.write(buffer, 0, count);
                }
                if (received != release.size) throw new IOException("The download was interrupted. Try downloading again.");
                if (!release.digest.isEmpty() && !release.digest.substring(7).equalsIgnoreCase(hex(digest.digest())))
                    throw new IOException("The update checksum does not match GitHub. Try downloading again.");
                output.getFD().sync();
            }
            complete = true;
        } finally {
            connection.disconnect();
            if (!complete) destination.delete();
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte b : value) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
