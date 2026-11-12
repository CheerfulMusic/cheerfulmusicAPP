package com.qingfeng.music.server.invite;

import java.net.*;
import java.net.http.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.work.api.WorkApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "qingfeng.invite-gate.enabled=true", "qingfeng.invite-gate.codes=QF-TEST-INVITATION-2026",
    "qingfeng.invite-gate.signing-key=test-only-private-signing-key-with-more-than-32-characters", "qingfeng.invite-gate.attempts-per-minute=100"
})
@ActiveProfiles("test")
class InviteGateIntegrationTest {
    @Value("${local.server.port}") int port;
    private static final String ARTIST_PROFILE = "/api/app/artist-profile";
    record Browser(HttpClient http, CookieManager cookies, String csrf) {}
    private record ArtistSession(HttpClient http, String csrf, String creator) {}
    private record ArtistRelease(String work, String id) {}
    private HttpResponse<String> send(HttpClient client, String method, String path, String body, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (csrf != null) builder.header("X-XSRF-TOKEN", csrf);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String field(String json, String key) {
        var matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(), json); return matcher.group(1);
    }
    private Browser browser() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var http = HttpClient.newBuilder().cookieHandler(cookies).build();
        var response = send(http, "GET", "/api/public/invite/status", null, null);
        assertEquals(200, response.statusCode()); assertTrue(response.body().contains("\"admitted\":false"));
        assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
        assertFalse(response.body().contains("QF-TEST-INVITATION")); assertFalse(response.body().contains("signing-key"));
        return new Browser(http, cookies, field(response.body(), "csrfToken"));
    }
    private HttpResponse<String> enter(Browser browser) throws Exception { return send(browser.http(), "POST", "/api/public/invite/enter", "{\"code\":\" qf-test-invitation-2026 \"}", browser.csrf()); }

    @Test void noInvitationBlocksAccountRecoveryGoogleAndDirectMediaRequests() throws Exception {
        var browser = browser();
        for (String path : List.of("/api/public/system/info", "/api/public/csrf", "/api/public/auth/google/status", "/api/app/works", "/api/public/gifts/some-token", "/media/sample-love-v2.mp3")) {
            var response = send(browser.http(), "GET", path, null, null);
            assertEquals(403, response.statusCode(), path); assertTrue(response.body().contains("INVITE_REQUIRED"), response.body());
            assertNotEquals(302, response.statusCode(), path + " 门禁下不得签发 302");
        }
        for (String path : List.of("/api/public/session", "/api/public/session/recover", "/api/public/auth/google/start")) {
            var response = send(browser.http(), "POST", path, "{}", browser.csrf());
            assertEquals(403, response.statusCode(), path); assertTrue(response.body().contains("INVITE_REQUIRED"));
        }
        assertEquals(200, send(browser.http(), "GET", "/actuator/health", null, null).statusCode());
        var callback = send(browser.http(), "GET", "/api/public/auth/google/callback?code=not-real&state=not-real", null, null);
        assertEquals(302, callback.statusCode()); assertEquals("/account", callback.headers().firstValue("location").orElseThrow());
    }
    @Test void invitationRequiresCsrfAndWrongCodeNeverCreatesAdmission() throws Exception {
        var browser = browser();
        assertEquals(403, send(browser.http(), "POST", "/api/public/invite/enter", "{\"code\":\"QF-TEST-INVITATION-2026\"}", null).statusCode());
        var wrong = send(browser.http(), "POST", "/api/public/invite/enter", "{\"code\":\"incorrect\"}", browser.csrf());
        assertEquals(403, wrong.statusCode()); assertTrue(wrong.body().contains("INVITE_INVALID"));
        assertFalse(wrong.headers().allValues("set-cookie").stream().anyMatch(cookie -> cookie.startsWith(InviteGateService.COOKIE + "=")));
        assertTrue(send(browser.http(), "GET", "/api/public/invite/status", null, null).body().contains("\"admitted\":false"));
    }
    @Test void admittedBrowserRemainsAdmittedAndStillNeedsItsOwnCreatorSession() throws Exception {
        var browser = browser(); var entered = enter(browser); assertEquals(200, entered.statusCode(), entered.body());
        String cookie = entered.headers().allValues("set-cookie").stream().filter(value -> value.startsWith(InviteGateService.COOKIE + "=")).findFirst().orElseThrow();
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Lax")); assertTrue(cookie.contains("Path=/"));
        assertTrue(send(browser.http(), "GET", "/api/public/invite/status", null, null).body().contains("\"admitted\":true"));
        assertEquals(200, send(browser.http(), "GET", "/api/public/system/info", null, null).statusCode());
        assertEquals(401, send(browser.http(), "GET", "/api/app/works", null, null).statusCode());
        assertEquals(200, send(browser.http(), "POST", "/api/public/session", "{}", browser.csrf()).statusCode());
        assertEquals(200, send(browser.http(), "GET", "/api/app/works", null, null).statusCode());
        var another = browser(); assertTrue(send(another.http(), "GET", "/api/public/invite/status", null, null).body().contains("\"admitted\":false"));
        String creatorCookie = browser.cookies().getCookieStore().getCookies().stream().filter(value -> value.getName().equals("QF_SESSION")).findFirst().orElseThrow().getValue();
        var forged = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/app/works")).header("Cookie", "QF_SESSION=" + creatorCookie).build();
        var denied = HttpClient.newHttpClient().send(forged, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode()); assertTrue(denied.body().contains("INVITE_REQUIRED"));
        browser.cookies().getCookieStore().getCookies().stream().filter(value -> value.getName().equals(InviteGateService.COOKIE)).forEach(value -> value.setValue("forged"));
        assertEquals(403, send(browser.http(), "GET", "/api/app/works", null, null).statusCode());
    }
    @Test void providerWebhooksAreNotMistakenForInteractiveVisitors() throws Exception {
        for (String path : List.of("/api/public/payments/airwallex/webhook", "/api/public/paypal/webhook"))
            assertFalse(send(HttpClient.newHttpClient(), "POST", path, "{}", null).body().contains("INVITE_REQUIRED"));
    }

    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired IdentityApi identity;
    @Autowired LyricsApi lyrics;
    @Autowired WorkApi works;
    @Autowired MediaApi media;
    @Autowired SharingApi sharing;
    @Autowired CreditsLedger credits;

    @Test void sharedSongIsReadableWithoutInvitationOrSessionButOwnerRoutesStayPrivate() throws Exception {
        var owner = browser(); assertEquals(200, enter(owner).statusCode());
        String csrf = field(send(owner.http(), "GET", "/api/public/csrf", null, null).body(), "token");
        assertEquals(200, send(owner.http(), "POST", "/api/public/session", "{}", csrf).statusCode());
        String work = field(send(owner.http(), "POST", "/api/app/works", "{\"recipient\":\"朋友\",\"occasion\":\"生日\",\"story\":\"private-source-story\",\"message\":\"生日快乐\",\"style\":\"民谣\",\"title\":\"公开收听测试\"}", csrf).body(), "id");
        String base = "/api/app/works/" + work;
        String revision = field(send(owner.http(), "POST", base + "/lyrics", "{\"workVersion\":0,\"title\":\"公开收听测试\",\"lyrics\":\"愿你一直快乐\"}", csrf).body(), "id");
        assertEquals(200, send(owner.http(), "POST", base + "/lyrics/" + revision + "/confirm", "{}", csrf).statusCode());
        var wav = java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);
        String asset = field(upload(owner, csrf, base + "/audio", "audio/wav", "sample.wav", wav.array()).body(), "id");
        var image = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(48,48,java.awt.image.BufferedImage.TYPE_INT_RGB), "png", image);
        String cover = field(upload(owner, csrf, base + "/covers", "image/png", "cover.png", image.toByteArray()).body(), "id");
        assertEquals(200, send(owner.http(), "PUT", base + "/covers/selection", "{\"coverId\":\"" + cover + "\",\"expectedVersion\":0}", csrf).statusCode());
        String token = field(send(owner.http(), "POST", base + "/shares", "{\"revisionId\":\"" + revision + "\",\"assetId\":\"" + asset + "\"}", csrf).body(), "token");
        String gift = "/api/public/gifts/" + token;
        // This client never enters an invitation and never creates an account/session.
        var guest = HttpClient.newHttpClient();
        assertEquals(200, send(guest, "GET", "/brand/share-default-cover.png", null, null).statusCode());
        assertEquals(200, send(guest, "HEAD", "/brand/share-default-cover.png", null, null).statusCode());
        assertEquals(200, send(guest, "GET", "/gift/" + token, null, null).statusCode());
        var snapshot = send(guest, "GET", gift, null, null);
        assertEquals(200, snapshot.statusCode(), snapshot.body());
        assertTrue(snapshot.body().contains("愿你一直快乐"));
        assertFalse(snapshot.body().contains("private-source-story"));
        assertFalse(snapshot.body().contains("ownerId"));
        assertEquals(200, send(guest, "HEAD", gift + "/audio", null, null).statusCode());
        assertEquals(200, send(guest, "GET", gift + "/cover", null, null).statusCode());
        var audio = guest.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + gift + "/audio")).header("Range", "bytes=0-11").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, audio.statusCode()); assertEquals(12, audio.body().length);
        for (String privatePath : List.of(base, base + "/audio/" + asset, base + "/covers/" + cover))
            assertEquals(403, send(guest, "GET", privatePath, null, null).statusCode());
        for (String path : List.of(gift + "/edit", gift + "/audio/extra", gift))
            assertEquals(403, send(guest, "POST", path, "{}", null).statusCode());
        assertEquals(200, send(owner.http(), "DELETE", base + "/shares", null, csrf).statusCode());
        for (String path : List.of(gift, gift + "/audio", gift + "/cover"))
            assertEquals(404, send(guest, "GET", path, null, null).statusCode());
        String nextToken = field(send(owner.http(), "POST", base + "/shares", "{\"revisionId\":\"" + revision + "\",\"assetId\":\"" + asset + "\"}", csrf).body(), "token");
        jdbc.update("UPDATE share_link SET expires_at=? WHERE work_id=?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)), work);
        assertEquals(404, send(guest, "GET", "/api/public/gifts/" + nextToken, null, null).statusCode());
        assertEquals(404, send(guest, "GET", "/api/public/gifts/" + nextToken + "/audio", null, null).statusCode());
    }

    @Test void giftDownloadRequiresAdmissionVerifiedIdentityAndCsrf() throws Exception {
        String owner = identity.create().creatorId();
        var work = works.create(owner, new WorkApi.Draft("Friend", "生日", "Story", "Message", "民谣", "Gift"));
        var revision = lyrics.save(owner, work.id(), work.version(), "Gift", "A line to sing");
        lyrics.confirm(owner, work.id(), revision.id());
        byte[] sample = wav(17);
        var asset = media.importAudio(owner, work.id(), new ByteArrayInputStream(sample));
        String gift = "/api/public/gifts/" + sharing.create(owner, work.id(), revision.id(), asset.id()).token();
        String unlock = gift + "/download";
        String attachment = gift + "/audio?download=true";

        var visitor = HttpClient.newHttpClient();
        var publicAudio = readBytes(visitor, gift + "/audio?download=false");
        assertEquals(200, publicAudio.statusCode());
        assertArrayEquals(sample, publicAudio.body());
        assertTrue(send(visitor, "GET", unlock, null, null).body().contains("INVITE_REQUIRED"));
        assertTrue(send(visitor, "GET", gift + "/audio?download=on", null, null).body().contains("INVITE_REQUIRED"));

        var recipient = browser();
        assertEquals(200, enter(recipient).statusCode());
        String csrf = field(send(recipient.http(), "GET", "/api/public/csrf", null, null).body(), "token");
        var session = send(recipient.http(), "POST", "/api/public/session", "{}", csrf);
        assertEquals(200, session.statusCode(), session.body());
        String creator = field(session.body(), "creatorId");
        var guest = send(recipient.http(), "GET", unlock, null, null);
        assertEquals(409, guest.statusCode(), guest.body());
        assertTrue(guest.body().contains("DOWNLOAD_LOGIN_REQUIRED"));
        linkFixtureAccount(creator);
        credits.credit(creator, 20, "gift-download-test:" + creator);
        long before = credits.balance(creator);
        var locked = send(recipient.http(), "GET", unlock, null, null);
        assertEquals(200, locked.statusCode(), locked.body());
        assertTrue(locked.body().contains("\"unlocked\":false"));
        assertTrue(locked.body().contains("\"price\":10"));
        assertEquals(404, readBytes(recipient.http(), attachment).statusCode());
        assertEquals(403, send(recipient.http(), "POST", unlock, "{}", null).statusCode());
        assertEquals(before, credits.balance(creator));

        for (int attempt = 0; attempt < 2; attempt++) {
            var unlocked = send(recipient.http(), "POST", unlock, "{}", csrf);
            assertEquals(200, unlocked.statusCode(), unlocked.body());
            assertTrue(unlocked.body().contains("\"unlocked\":true"));
            assertEquals(before - 10, credits.balance(creator));
        }
        var downloaded = readBytes(recipient.http(), attachment);
        assertEquals(200, downloaded.statusCode());
        assertTrue(downloaded.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment"));
        assertArrayEquals(sample, downloaded.body());

        var other = artistSession("Other download recipient");
        assertEquals(404, readBytes(other.http(), attachment).statusCode());
        sharing.revokeAll(owner, work.id());
        assertEquals(404, readBytes(visitor, gift + "/audio").statusCode());
        assertArrayEquals(sample, readBytes(recipient.http(), attachment).body());
    }

    @Test void artistPublicationPinsTheAudioAndClosesGuestAccessWhenRemovedOrHidden() throws Exception {
        String mine = "/api/app/artist-profile";
        var owner = browser();
        assertEquals(200, enter(owner).statusCode());
        String csrf = field(send(owner.http(), "GET", "/api/public/csrf", null, null).body(), "token");
        String creator = field(send(owner.http(), "POST", "/api/public/session", "{}", csrf).body(), "creatorId");
        assertEquals(403, send(owner.http(), "GET", mine, null, null).statusCode());
        linkFixtureAccount(creator);

        var visitor = HttpClient.newHttpClient();
        var initial = send(owner.http(), "GET", mine, null, null);
        assertEquals(200, initial.statusCode());
        assertTrue(initial.body().contains("\"publicId\":null"));
        var image = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(300, 200, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", image);
        assertEquals(400, saveArtist(owner, csrf, "Artist", "<svg/>".getBytes(StandardCharsets.UTF_8)).statusCode());
        var saved = saveArtist(owner, csrf, "Artist", image.toByteArray());
        assertEquals(200, saved.statusCode(), saved.body());
        String artist = field(saved.body(), "publicId");
        String publicPath = "/api/public/artists/" + artist;
        String avatarKey = field(saved.body(), "avatarKey");
        String avatar = publicPath + "/avatar/" + avatarKey;
        assertEquals(404, send(visitor, "GET", publicPath, null, null).statusCode());
        assertEquals(404, send(visitor, "GET", avatar, null, null).statusCode());
        assertEquals(200, send(owner.http(), "GET", mine + "/avatar/" + avatarKey, null, null).statusCode());
        assertEquals(403, send(owner.http(), "PUT", mine + "/visibility", "{\"published\":true}", null).statusCode());
        assertEquals(200, send(owner.http(), "PUT", mine + "/visibility", "{\"published\":true}", csrf).statusCode());
        var publicAvatar = visitor.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + avatar))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, publicAvatar.statusCode());
        assertEquals(192, javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(publicAvatar.body())).getWidth());
        assertEquals(403, send(visitor, "GET", mine, null, null).statusCode());

        String work = works.create(creator, new WorkApi.Draft("private-recipient", "生日", "private-story",
                "private-message", "民谣", "Song")).id();
        byte[] firstBytes = wav(0);
        byte[] secondBytes = wav(7);
        String first = media.importAudio(creator, work, new java.io.ByteArrayInputStream(firstBytes)).id();
        String second = media.importAudio(creator, work, new java.io.ByteArrayInputStream(secondBytes)).id();
        String cover = field(upload(owner, csrf, "/api/app/works/" + work + "/covers", "image/png", "cover.png",
                image.toByteArray()).body(), "id");
        String releasePath = mine + "/releases/" + work;
        String firstRequest = songRequest(first, cover);
        var other = browser();
        assertEquals(200, enter(other).statusCode());
        String otherCsrf = field(send(other.http(), "GET", "/api/public/csrf", null, null).body(), "token");
        String otherCreator = field(send(other.http(), "POST", "/api/public/session", "{}", otherCsrf).body(), "creatorId");
        linkFixtureAccount(otherCreator);
        assertEquals(200, saveArtist(other, otherCsrf, "Artist", null).statusCode());
        assertEquals(404, send(other.http(), "PUT", releasePath, firstRequest, otherCsrf).statusCode());

        var published = send(owner.http(), "PUT", releasePath, firstRequest, csrf);
        assertEquals(200, published.statusCode(), published.body());
        assertEquals(published.body(), send(owner.http(), "PUT", releasePath, firstRequest, csrf).body());
        String songPath = publicPath + "/songs/" + field(published.body(), "id");
        assertEquals(200, send(visitor, "GET", songPath + "/1/cover", null, null).statusCode());
        var page = send(visitor, "GET", publicPath, null, null);
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.body().contains("\"title\":\"Song\""));
        for (String secret : List.of("private-recipient", "private-story", "private-message", creator, work, first, cover))
            assertFalse(page.body().contains(secret));
        media.select(creator, work, second, media.selection(creator, work).version());
        assertArrayEquals(firstBytes, readBytes(visitor, songPath + "/1/audio").body());
        var replacement = send(owner.http(), "PUT", releasePath, songRequest(second, cover), csrf);
        assertEquals(200, replacement.statusCode(), replacement.body());
        assertEquals(404, readBytes(visitor, songPath + "/1/audio").statusCode());
        var ranged = visitor.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + songPath + "/2/audio"))
                .header("Range", "bytes=44-47").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, ranged.statusCode());
        assertArrayEquals(new byte[]{7, 0, 0, 0}, ranged.body());

        assertEquals(artist, field(saveArtist(owner, csrf, "New name", null).body(), "publicId"));
        assertTrue(send(visitor, "GET", publicPath, null, null).body().contains("New name"));
        assertEquals(200, send(owner.http(), "PUT", mine + "/visibility", "{\"published\":false}", csrf).statusCode());
        for (String path : List.of(publicPath, avatar, songPath + "/2/audio", songPath + "/2/cover"))
            assertEquals(404, send(visitor, "GET", path, null, null).statusCode(), path);
        assertEquals(200, send(owner.http(), "PUT", mine + "/visibility", "{\"published\":true}", csrf).statusCode());
        assertEquals(200, readBytes(visitor, songPath + "/2/audio").statusCode());
        assertEquals(200, send(owner.http(), "DELETE", releasePath, null, csrf).statusCode());
        assertEquals(404, readBytes(visitor, songPath + "/2/audio").statusCode());
        assertTrue(send(visitor, "GET", publicPath, null, null).body().contains("\"songs\":[]"));
        assertEquals(second, media.selection(creator, work).assetId());
        assertEquals(200, send(owner.http(), "PUT", releasePath, songRequest(second, cover), csrf).statusCode());
        assertEquals(200, readBytes(visitor, songPath + "/4/audio").statusCode());
        works.delete(creator, work, true);
        assertEquals(404, readBytes(visitor, songPath + "/4/audio").statusCode());
        assertTrue(send(visitor, "GET", publicPath, null, null).body().contains("\"songs\":[]"));
    }

    @Test void artistHomepageCoverAndFeaturedSongRespectPublicationAndOwnership() throws Exception {
        var owner = artistSession("Custom artist");
        var older = releaseArtistSong(owner, "Older song", 7);
        var latest = releaseArtistSong(owner, "Latest song", 8);
        var other = artistSession("Other artist");
        var foreign = releaseArtistSong(other, "Foreign song", 9);
        byte[] cover = bannerImage(Color.PINK);
        byte[] replacementCover = bannerImage(Color.BLUE);
        var pinned = postArtistProfile(owner.http(), owner.csrf(), Map.of(
                "stageName", "Custom artist", "tagline", "Custom tagline", "featuredReleaseId", older.id()), "cover", cover);
        assertEquals(200, pinned.statusCode(), pinned.body());
        assertEquals(older.id(), field(pinned.body(), "featuredReleaseId"));
        String coverKey = field(pinned.body(), "coverKey");
        String publicPath = "/api/public/artists/" + field(pinned.body(), "publicId");
        String publicCover = publicPath + "/cover/" + coverKey;
        String privateCover = ARTIST_PROFILE + "/cover/" + coverKey;

        var visitor = HttpClient.newHttpClient();
        var ownerImage = readBytes(owner.http(), privateCover);
        assertEquals(200, ownerImage.statusCode());
        assertEquals("image/jpeg", ownerImage.headers().firstValue("content-type").orElse(""));
        var decodedCover = ImageIO.read(new ByteArrayInputStream(ownerImage.body()));
        assertNotNull(decodedCover);
        assertEquals(640, decodedCover.getWidth());
        assertEquals(240, decodedCover.getHeight());
        assertEquals(404, send(other.http(), "GET", privateCover, null, null).statusCode());
        assertEquals(403, send(visitor, "GET", privateCover, null, null).statusCode());
        assertEquals(404, send(visitor, "GET", publicPath, null, null).statusCode());
        assertEquals(404, send(visitor, "GET", publicCover, null, null).statusCode());
        assertEquals(404, send(visitor, "HEAD", publicCover, null, null).statusCode());

        assertEquals(200, send(owner.http(), "PUT", ARTIST_PROFILE + "/visibility",
                "{\"published\":true}", owner.csrf()).statusCode());
        var published = send(visitor, "GET", publicPath, null, null);
        assertEquals(200, published.statusCode(), published.body());
        assertEquals(older.id(), field(published.body(), "featuredReleaseId"));
        assertEquals(coverKey, field(published.body(), "coverKey"));
        assertEquals(latest.id(), field(published.body(), "id"), "Pinning should not change the order of songs");
        var guestImage = readBytes(visitor, publicCover);
        assertEquals(200, guestImage.statusCode());
        assertArrayEquals(ownerImage.body(), guestImage.body());
        assertEquals(200, send(visitor, "HEAD", publicCover, null, null).statusCode());

        var ordinaryEdit = postArtistProfile(owner.http(), owner.csrf(), Map.of(
                "stageName", "Renamed artist", "tagline", "Renamed tagline"), "cover", null);
        assertEquals(200, ordinaryEdit.statusCode(), ordinaryEdit.body());
        assertEquals(coverKey, field(ordinaryEdit.body(), "coverKey"));
        assertEquals(older.id(), field(ordinaryEdit.body(), "featuredReleaseId"));
        assertFeaturedUpdateRejected(owner, foreign.id(), replacementCover);
        assertArrayEquals(ownerImage.body(), readBytes(visitor, publicCover).body());

        var automatic = postArtistProfile(owner.http(), owner.csrf(), Map.of(
                "stageName", "Renamed artist", "tagline", "Renamed tagline", "featuredReleaseId", ""), "cover", null);
        assertEquals(200, automatic.statusCode(), automatic.body());
        assertTrue(automatic.body().contains("\"featuredReleaseId\":null"), automatic.body());
        var publicAutomatic = send(visitor, "GET", publicPath, null, null);
        assertEquals(200, publicAutomatic.statusCode(), publicAutomatic.body());
        assertTrue(publicAutomatic.body().contains("\"featuredReleaseId\":null"), publicAutomatic.body());
        assertEquals(latest.id(), field(publicAutomatic.body(), "id"));

        var repinned = postArtistProfile(owner.http(), owner.csrf(), Map.of(
                "stageName", "Renamed artist", "tagline", "Renamed tagline", "featuredReleaseId", older.id()), "cover", null);
        assertEquals(200, repinned.statusCode(), repinned.body());
        assertEquals(older.id(), field(repinned.body(), "featuredReleaseId"));
        assertEquals(200, send(owner.http(), "DELETE", ARTIST_PROFILE + "/releases/" + older.work(),
                null, owner.csrf()).statusCode());
        var afterRemoval = send(visitor, "GET", publicPath, null, null);
        assertEquals(200, afterRemoval.statusCode(), afterRemoval.body());
        assertTrue(afterRemoval.body().contains("\"featuredReleaseId\":null"), afterRemoval.body());
        assertEquals(latest.id(), field(afterRemoval.body(), "id"));
        assertFeaturedUpdateRejected(owner, older.id(), replacementCover);
        assertArrayEquals(ownerImage.body(), readBytes(visitor, publicCover).body());

        var resetCover = postArtistProfile(owner.http(), owner.csrf(), Map.of(
                "stageName", "Renamed artist", "tagline", "Renamed tagline", "removeCover", "true"), "cover", null);
        assertEquals(200, resetCover.statusCode(), resetCover.body());
        assertTrue(resetCover.body().contains("\"coverKey\":null"), resetCover.body());
        var publicReset = send(visitor, "GET", publicPath, null, null);
        assertEquals(200, publicReset.statusCode(), publicReset.body());
        assertTrue(publicReset.body().contains("\"coverKey\":null"), publicReset.body());
        assertEquals(404, send(visitor, "GET", publicCover, null, null).statusCode());
        assertEquals(404, send(visitor, "HEAD", publicCover, null, null).statusCode());
        assertEquals(404, send(owner.http(), "GET", privateCover, null, null).statusCode());
    }

    private ArtistSession artistSession(String name) throws Exception {
        var browser = browser();
        assertEquals(200, enter(browser).statusCode());
        String csrf = field(send(browser.http(), "GET", "/api/public/csrf", null, null).body(), "token");
        var session = send(browser.http(), "POST", "/api/public/session", "{}", csrf);
        assertEquals(200, session.statusCode(), session.body());
        String creator = field(session.body(), "creatorId");
        linkFixtureAccount(creator);
        var profile = saveArtist(browser, csrf, name, null);
        assertEquals(200, profile.statusCode(), profile.body());
        return new ArtistSession(browser.http(), csrf, creator);
    }

    private ArtistRelease releaseArtistSong(ArtistSession owner, String title, int sample) throws Exception {
        String work = works.create(owner.creator(),
                new WorkApi.Draft("Friend", "生日", "Story", "Message", "民谣", title)).id();
        String asset = media.importAudio(owner.creator(), work, new ByteArrayInputStream(wav(sample))).id();
        var release = send(owner.http(), "PUT", ARTIST_PROFILE + "/releases/" + work,
                "{\"assetId\":\"" + asset + "\",\"title\":\"" + title + "\"}", owner.csrf());
        assertEquals(200, release.statusCode(), release.body());
        return new ArtistRelease(work, field(release.body(), "id"));
    }

    private void assertFeaturedUpdateRejected(ArtistSession owner, String release, byte[] cover) throws Exception {
        var before = send(owner.http(), "GET", ARTIST_PROFILE, null, null);
        assertEquals(200, before.statusCode(), before.body());
        var rejected = postArtistProfile(owner.http(), owner.csrf(), Map.of(
                "stageName", "Rejected name", "tagline", "Rejected tagline", "featuredReleaseId", release), "cover", cover);
        assertEquals(400, rejected.statusCode(), rejected.body());
        assertTrue(rejected.body().contains("INVALID_ARTIST_FEATURED_RELEASE"), rejected.body());
        var after = send(owner.http(), "GET", ARTIST_PROFILE, null, null);
        assertEquals(200, after.statusCode(), after.body());
        assertEquals(before.body(), after.body(), "Rejected update should preserve all profile fields");
    }

    private static byte[] bannerImage(Color color) throws Exception {
        var image = new BufferedImage(640, 240, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private void linkFixtureAccount(String creator) {
        jdbc.update("""
                INSERT INTO creator_oidc_identity(subject_hash,creator_id,display_name,created_at,updated_at)
                VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), creator, "Fixture account");
    }

    private HttpResponse<String> saveArtist(Browser browser, String csrf, String name, byte[] avatar) throws Exception {
        return postArtistProfile(browser.http(), csrf, Map.of("stageName", name, "tagline", "A test tagline"), "avatar", avatar);
    }

    private HttpResponse<String> postArtistProfile(HttpClient client, String csrf, Map<String, String> fields,
            String imageField, byte[] image) throws Exception {
        String boundary = "ArtistBoundary";
        var body = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                    + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (image != null) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + imageField + "\"; filename=\"image.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(image);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ARTIST_PROFILE))
                .header("X-XSRF-TOKEN", csrf).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String songRequest(String asset, String cover) {
        return "{\"assetId\":\"" + asset + "\",\"coverId\":\"" + cover + "\",\"title\":\"Song\"}";
    }

    private static byte[] wav(int sample) {
        return ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(40)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(4).putInt(sample).array();
    }

    private HttpResponse<byte[]> readBytes(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> upload(Browser browser, String csrf, String path, String type, String filename, byte[] data) throws Exception {
        String boundary = "ShareBoundary";
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body = java.nio.ByteBuffer.allocate(prefix.length + data.length + suffix.length).put(prefix).put(data).put(suffix).array();
        var response = browser.http().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("X-XSRF-TOKEN", csrf).header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body()); return response;
    }
}
