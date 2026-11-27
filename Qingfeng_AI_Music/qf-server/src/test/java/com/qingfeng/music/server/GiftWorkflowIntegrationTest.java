package com.qingfeng.music.server;

import com.qingfeng.music.generation.api.GenerationApi.Task;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.infrastructure.persistence.generation.MyBatisTaskRepository;
import com.qingfeng.music.infrastructure.persistence.generation.TaskMapper;
import com.qingfeng.music.media.application.MediaService;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.test.context.jdbc.Sql(scripts={"classpath:db/migration/V54__song_share_statistics.sql","classpath:db/optional/public_song_listening.sql"}, executionPhase=org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class GiftWorkflowIntegrationTest {
 @Value("${local.server.port}")int port;
 @Autowired TaskMapper tasks;
 @Autowired JdbcTemplate jdbc;
 @Autowired MediaService media;
 record Session(HttpClient client,String csrf){}
 String text(HttpResponse<byte[]> response){return new String(response.body(),StandardCharsets.UTF_8);}
 String field(String json,String key){var m=Pattern.compile("\""+key+"\"\\s*:\\s*\"([^\"]+)\"").matcher(json);assertTrue(m.find(),"缺少"+key);return m.group(1);}
 HttpResponse<byte[]> call(Session s,String method,String path,String body)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
  if(s!=null&&s.csrf()!=null)b.header("X-XSRF-TOKEN",s.csrf());if(body!=null)b.header("Content-Type","application/json");
  return (s==null?HttpClient.newHttpClient():s.client()).send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
 }
 Session session()throws Exception{var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();var s=new Session(client,null);String csrf=field(text(call(s,"GET","/api/public/csrf",null)),"token");s=new Session(client,csrf);assertEquals(200,call(s,"POST","/api/public/session","{}").statusCode());return s;}
 final String draft="{\"recipient\":\"小雨\",\"occasion\":\"恋爱纪念\",\"story\":\"secret-only-story\",\"message\":\"原始祝福\",\"style\":\"民谣\",\"title\":\"故事\"}";
 String work(Session s)throws Exception{return field(text(call(s,"POST","/api/app/works",draft)),"id");}
 String lyrics(Session s,String w,String value)throws Exception{
  String body = new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("workVersion",0,"title","给你的歌","lyrics",value));
  return field(text(call(s,"POST","/api/app/works/"+w+"/lyrics",body)),"id");
 }
 byte[] wav(){var b=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);return b.array();}
 HttpResponse<byte[]> upload(Session s,String w,byte[] data)throws Exception{
  String boundary="QingfengBoundary";var prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"../../secret.html\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8);var suffix=("\r\n--"+boundary+"--\r\n").getBytes();
  var body=ByteBuffer.allocate(prefix.length+data.length+suffix.length).put(prefix).put(data).put(suffix).array();
  return s.client().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/app/works/"+w+"/audio")).header("X-XSRF-TOKEN",s.csrf()).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
 }
 String share(Session s,String w,String revision,String asset)throws Exception{var response=call(s,"POST","/api/app/works/"+w+"/shares","{\"revisionId\":\""+revision+"\",\"assetId\":\""+asset+"\"}");assertEquals(200,response.statusCode(),text(response));return field(text(response),"token");}
 @Test void lyricVersionsAreImmutableAndOwned()throws Exception{
  var a=session();var b=session();String w=work(a),r=lyrics(a,w,"第一版歌词");
  assertEquals(200,call(a,"POST","/api/app/works/"+w+"/lyrics/"+r+"/confirm","{}").statusCode());
  lyrics(a,w,"第二版歌词");String all=text(call(a,"GET","/api/app/works/"+w+"/lyrics",null));assertTrue(all.contains("第一版歌词"));assertTrue(all.contains("第二版歌词"));assertTrue(all.contains("\"confirmed\":true"));
  assertEquals(404,call(b,"GET","/api/app/works/"+w+"/lyrics",null).statusCode());
 }
 @Test void uploadedAudioCanBeSharedWithoutLeakingStoryAndRevoked()throws Exception{
  var a=session();var b=session();String w=work(a),r=lyrics(a,w,"我们一起看海");call(a,"POST","/api/app/works/"+w+"/lyrics/"+r+"/confirm","{}");
  var upload=upload(a,w,wav());assertEquals(200,upload.statusCode(),text(upload));String asset=field(text(upload),"id");assertTrue(text(upload).contains("IMPORTED"));assertFalse(text(upload).contains("storageKey"));
  assertEquals(404,call(b,"GET","/api/app/works/"+w+"/audio/"+asset,null).statusCode());
  String token=share(a,w,r,asset),publicPath="/api/public/gifts/"+token;
  var gift=call(null,"GET",publicPath,null);assertEquals(200,gift.statusCode());assertTrue(text(gift).contains("原始祝福"));assertFalse(text(gift).contains("secret-only-story"));assertFalse(text(gift).contains("ownerId"));
  assertEquals(404,call(null,"GET","/song/"+token,null).statusCode());
  var grant = call(a,"POST","/api/app/works/"+w+"/shares","{\"assetId\":\""+asset+"\",\"revisionId\":\""+r+"\",\"publicSong\":true}");
  String songId=field(text(grant),"songId");
  String songApi="/api/songs/"+songId;
  assertEquals(200,call(null,"GET",songApi+"/public",null).statusCode());
  String page = text(call(null,"GET","/song/"+songId,null));
  String timelineTitle = "青风音乐 Cheerful Music｜给你的歌 · 匿名创作者";
  assertTrue(page.contains("<title>" + timelineTitle + "</title>"));
  assertTrue(page.contains("property=\"og:title\" content=\"" + timelineTitle + "\""));
  assertTrue(page.contains("property=\"og:site_name\" content=\"青风音乐 Cheerful Music\""));
  String canonical = "https://cheerfulmusic.com/song/" + songId;
  String thumbnail = "https://cheerfulmusic.com" + songApi + "/thumbnail.png";
  assertTrue(page.contains("rel=\"canonical\" href=\"" + canonical + "\""));
  assertTrue(page.contains("property=\"og:url\" content=\"" + canonical + "\""));
  assertTrue(page.contains("property=\"og:image\" content=\"" + thumbnail + "\""));
  assertTrue(page.contains("property=\"og:description\" content=\"我在 Cheerful Music 创作了一首歌曲，邀请你来听听\""));
  assertTrue(page.contains("name=\"twitter:card\" content=\"summary\""));
  assertTrue(page.contains("name=\"twitter:image\" content=\"" + thumbnail + "\""));
  var english = call(null, "GET", "/song/" + songId + "?lang=en&from=example", null);
  assertEquals(200, english.statusCode());
  String englishPage = text(english);
  assertTrue(englishPage.contains("rel=\"canonical\" href=\"" + canonical + "?lang=en\""));
  assertTrue(englishPage.contains("property=\"og:url\" content=\"" + canonical + "?lang=en\""));
  assertTrue(englishPage.contains("property=\"og:title\" content=\"给你的歌\""));
  assertTrue(englishPage.contains("name=\"twitter:description\" content=\"Come listen to “给你的歌” on Cheerful Music.\""));
  var anonymousCard = json(call(a,"GET","/api/app/works/"+w+"/shares/"+asset,null),200).path("card");
  assertEquals(timelineTitle,anonymousCard.path("timelineTitle").asString());
  assertEquals("匿名创作者",anonymousCard.path("creator").asString());
  assertArrayEquals(wav(),call(null,"GET",songApi+"/audio",null).body());
  assertEquals(200,call(null,"GET",songApi+"/thumbnail.png",null).statusCode());
  var preview=call(null,"GET","/gift/"+token,null);assertEquals(200,preview.statusCode());assertTrue(text(preview).contains("property=\"og:title\" content=\"给你的歌\""));assertFalse(text(preview).contains("secret-only-story"));
  assertEquals(200,call(null,"HEAD","/gift/"+token,null).statusCode());
  var ranged=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+publicPath+"/audio")).header("Range","bytes=0-11").GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(206,ranged.statusCode());assertEquals(12,ranged.body().length);
  assertNotEquals(302,call(null,"GET",publicPath+"/audio",null).statusCode(),"CDN 默认关闭时不得签发 302");
  assertEquals(404,call(b,"DELETE","/api/app/works/"+w+"/shares",null).statusCode());
  assertEquals(200,call(a,"DELETE","/api/app/works/"+w+"/shares",null).statusCode());
  assertEquals(404,call(null,"GET",publicPath,null).statusCode());assertEquals(404,call(null,"GET",publicPath+"/audio",null).statusCode());assertNotEquals(302,call(null,"GET",publicPath+"/audio",null).statusCode(),"撤销后不得签发 302");
  assertEquals(404,call(null,"GET",songApi+"/audio",null).statusCode());
  assertEquals(404,call(null,"GET",songApi+"/thumbnail.png",null).statusCode());
  assertEquals(404,call(null,"GET","/gift/"+token,null).statusCode());assertFalse(text(call(null,"GET","/gift/"+token,null)).contains("og:title"));
 }

 @Test void listeningPageUsesOnlyPublishedStoryAndAssetLyricsAndIsolatesInteractions() throws Exception {
  var owner = session();
  var listener = session();
  String snapshot = "# 《给你的歌》\n**[Verse 1]**\n窗外的 <海> & 风";
  String work = work(owner), listenerWork = work(listener), revision = lyrics(owner,work,snapshot);
  String api = "/api/app/works/" + work;
  assertEquals(200,call(owner,"POST",api+"/lyrics/"+revision+"/confirm","{}").statusCode());
  String asset = field(text(upload(owner,work,wav())),"id");
  String id = json(call(owner,"POST",api+"/shares","{\"assetId\":\""+asset+"\",\"revisionId\":\""+revision+"\",\"publicSong\":true}"),200).path("songId").asString();
  String pub = "/api/songs/"+id, write = "/api/app/song-listening/"+id;
  String page = text(call(null,"GET","/song/"+id,null));
  assertTrue(page.contains("窗外的 &lt;海&gt; &amp; 风"));
  assertFalse(page.contains("[Verse 1]"));
  assertFalse(page.contains("# 《给你的歌》"));
  assertFalse(page.contains("secret-only-story"));
  assertFalse(page.contains("data-start="));
  assertEquals(snapshot,json(call(null,"GET",pub+"/public",null),200).path("lyrics").asString());
  jdbc.update("INSERT INTO media_lyrics_timeline(asset_id,work_id,state,words_json,updated_at) VALUES(?,?,'READY',?,CURRENT_TIMESTAMP)",asset,work,
          "[{\"text\":\"听 <见>\",\"start\":2.5,\"end\":4.0}]");
  page = text(call(null,"GET","/song/"+id,null));
  assertTrue(page.contains("data-start=\"2.5\" data-end=\"4.0\"") && page.contains("听 &lt;见&gt;"));
  var empty = json(call(null,"GET",pub+"/community",null),200);
  assertEquals("",empty.path("story").asString());
  assertEquals(0,empty.path("commentCount").asLong());
  assertEquals(404,call(listener,"PUT",write+"/story","{\"text\":\"越权\"}").statusCode());
  assertEquals(200,call(owner,"PUT",write+"/story","{\"text\":\"只公开这一段\"}").statusCode());
  assertEquals("只公开这一段",json(call(null,"GET",pub+"/community",null),200).path("story").asString());
  assertEquals(403,call(listener,"PUT",write+"/reactions/like","{\"active\":true}").statusCode());
  String listenerId = jdbc.queryForObject("SELECT owner_id FROM music_work WHERE id=?",String.class,listenerWork);
  jdbc.update("INSERT INTO creator_oidc_identity(subject_hash,creator_id,created_at,updated_at) VALUES(?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          UUID.randomUUID().toString().replace("-","").repeat(2),listenerId);
  String ownerId = jdbc.queryForObject("SELECT owner_id FROM music_work WHERE id=?",String.class,work);
  String artist = UUID.randomUUID().toString();
  jdbc.update("INSERT INTO artist_profile(creator_id,public_id,stage_name,tagline,published) VALUES(?,?,?,'',TRUE)",ownerId,artist,"公开艺名");
  String namedTitle = "青风音乐 Cheerful Music｜给你的歌 · 公开艺名";
  assertTrue(text(call(null,"GET","/song/"+id,null)).contains("property=\"og:title\" content=\"" + namedTitle + "\""));
  String namedEnglish = text(call(null, "GET", "/song/" + id + "?lang=en", null));
  assertTrue(namedEnglish.contains("property=\"og:title\" content=\"给你的歌 · 公开艺名\""));
  assertTrue(namedEnglish.contains("property=\"og:description\" content=\"Come listen to “给你的歌” by 公开艺名 on Cheerful Music.\""));
  var namedCard = json(call(owner,"GET",api+"/shares/"+asset,null),200).path("card");
  assertEquals(namedTitle,namedCard.path("timelineTitle").asString());
  assertEquals("公开艺名",namedCard.path("creator").asString());
  assertEquals(artist,json(call(null,"GET",pub+"/public",null),200).path("artistId").asString());
  String comment = "/comments/"+UUID.randomUUID(), commentText="{\"text\":\"喜欢这首歌\"}", active="{\"active\":true}";
  for (int repeat=0; repeat<2; repeat++) {
   for (String kind : java.util.List.of("like","favorite","follow")) assertEquals(204,call(listener,"PUT",write+"/reactions/"+kind,active).statusCode());
   assertEquals(204,call(listener,"PUT",write+comment,commentText).statusCode());
   assertEquals(204,call(listener,"PUT",write+comment+"/like",active).statusCode());
  }
  var heard = json(call(listener,"GET",pub+"/community?sort=hot",null),200);
  for (String count : java.util.List.of("likes","favorites","followers","commentCount")) assertEquals(1,heard.path(count).asLong(),count);
  assertTrue(heard.path("liked").asBoolean());
  var first=heard.path("comments").get(0);
  assertEquals(1,first.path("likes").asLong());
  assertTrue(first.path("removable").asBoolean());
  var anonymous=json(call(null,"GET",pub+"/community?sort=new",null),200);
  assertFalse(anonymous.path("liked").asBoolean());
  assertFalse(anonymous.path("comments").get(0).path("removable").asBoolean());
  assertEquals(409,call(listener,"PUT",write+comment,"{\"text\":\"另一段\"}").statusCode());
  assertEquals(204,call(owner,"DELETE",write+comment,null).statusCode());
  assertEquals(404,call(listener,"PUT",write+comment+"/like",active).statusCode());
  assertEquals(200,call(owner,"PUT",write+"/story","{\"text\":\"\"}").statusCode());
  assertEquals("",json(call(null,"GET",pub+"/community",null),200).path("story").asString());
  assertEquals(200,call(owner,"DELETE",api+"/shares/"+asset,null).statusCode());
  assertEquals(404,call(null,"GET",pub+"/community",null).statusCode());
  assertEquals(404,call(listener,"PUT",write+comment,commentText).statusCode());
 }

private String albumRelease(Session s,String work,String title)throws Exception {
  String asset=field(text(upload(s,work,wav())),"id"), id=UUID.randomUUID().toString();
  jdbc.update("""
      INSERT INTO artist_song_release(id,creator_id,work_id,asset_id,title,version,show_on_profile,published_at)
      SELECT ?,owner_id,id,?,?,1,TRUE,CURRENT_TIMESTAMP FROM music_work WHERE id=?
      """,id,asset,title,work);
  return id;
 }
 private String albumArtist(String work,String name) {
  String owner=jdbc.queryForObject("SELECT owner_id FROM music_work WHERE id=?",String.class,work), artist=UUID.randomUUID().toString();
  jdbc.update("INSERT INTO creator_oidc_identity(subject_hash,creator_id,created_at,updated_at) VALUES(?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          UUID.randomUUID().toString().replace("-","").repeat(2),owner);
  jdbc.update("INSERT INTO artist_profile(creator_id,public_id,stage_name,tagline,published) VALUES(?,?,?,'',TRUE)",owner,artist,name);
  return artist;
 }
 private HttpResponse<byte[]> albumForm(Session session,String path,long version,boolean published,java.util.List<String> releases,byte[] cover)throws Exception {
  String boundary="AlbumFixture"+UUID.randomUUID();
  var body=new java.io.ByteArrayOutputStream();
  var fields=new java.util.ArrayList<java.util.Map.Entry<String,String>>();
  fields.add(java.util.Map.entry("version",String.valueOf(version)));fields.add(java.util.Map.entry("published",String.valueOf(published)));
  fields.add(java.util.Map.entry("title","我的首张专辑"));fields.add(java.util.Map.entry("description","公开的专辑简介"));
  for(String song:releases){
   fields.add(java.util.Map.entry("tracks",song));
   fields.add(java.util.Map.entry("versions",String.valueOf(jdbc.queryForObject("SELECT version FROM artist_song_release WHERE id=?",Long.class,song))));
  }
  for(var field:fields)body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));
  if(cover!=null){
   body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"cover\"; filename=\"cover.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
   body.write(cover);body.write("\r\n".getBytes(StandardCharsets.UTF_8));
  }
  body.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
  return session.client().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
      .header("X-XSRF-TOKEN",session.csrf()).header("Content-Type","multipart/form-data; boundary="+boundary)
      .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),HttpResponse.BodyHandlers.ofByteArray());
 }

 @Test void albumsAreOrderedOwnedAndRequirePublishedVersionsForEveryPublicRead()throws Exception {
  var owner=session();var other=session();
  String wa=work(owner),wb=work(owner),wx=work(other);
  String artist=albumArtist(wa,"风的声音"), foreignArtist=albumArtist(wx,"另一位作者");
  String first=albumRelease(owner,wa,"第一首"), second=albumRelease(owner,wb,"第二首"), foreign=albumRelease(other,wx,"别人的歌");
  var both=java.util.List.of(first,second);var reorder=java.util.List.of(second,first);
  String id=UUID.randomUUID().toString(),write="/api/app/artist-albums/"+id;
  String catalog="/api/public/artists/"+artist+"/albums",direct=catalog+"/"+id;
  var bytes=new java.io.ByteArrayOutputStream();
  javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(24,16,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
  var draft=json(albumForm(owner,write,0,false,both,bytes.toByteArray()),200);
  String key=draft.path("coverKey").asString();
  assertEquals(1,draft.path("version").asLong());
  assertEquals(0,json(call(null,"GET",catalog,null),200).size());
  assertEquals(404,call(null,"GET",direct,null).statusCode());
  assertEquals(404,call(null,"GET",direct+"/cover/"+key,null).statusCode());
  assertEquals(200,call(owner,"GET",write+"/cover/"+key,null).statusCode());
  assertEquals(404,call(other,"GET",write+"/cover/"+key,null).statusCode());
  assertEquals(400,albumForm(owner,write,1,true,java.util.List.of(first,foreign),null).statusCode());
  assertEquals(400,albumForm(owner,write,1,true,java.util.List.of(first,first),null).statusCode());
  assertEquals(404,albumForm(other,write,1,true,java.util.List.of(foreign),null).statusCode());
  assertEquals(200,albumForm(owner,write,1,true,reorder,null).statusCode());
  assertEquals(409,albumForm(owner,write,1,false,both,null).statusCode());
  assertEquals(1,json(call(null,"GET",catalog,null),200).size());
  var published=json(call(null,"GET",direct,null),200);
  assertEquals("第二首",published.path("tracks").get(0).path("title").asString());
  assertEquals(first,published.path("tracks").get(1).path("id").asString());
  var picture=call(null,"GET",direct+"/cover/"+key,null);
  assertEquals(200,picture.statusCode());
  assertEquals(800,javax.imageio.ImageIO.read(new ByteArrayInputStream(picture.body())).getWidth());
  assertEquals(404,call(null,"GET","/api/public/artists/"+foreignArtist+"/albums/"+id,null).statusCode());
  assertEquals(200,call(null,"GET","/artist/"+artist+"/albums/"+id,null).statusCode());
  jdbc.update("UPDATE artist_profile SET published=FALSE WHERE public_id=?",artist);
  assertEquals(404,call(null,"GET",direct+"/cover/"+key,null).statusCode());
  jdbc.update("UPDATE artist_profile SET published=TRUE WHERE public_id=?",artist);
  String firstAsset=jdbc.queryForObject("SELECT asset_id FROM artist_song_release WHERE id=?",String.class,first);
  jdbc.update("INSERT INTO media_asset_annotation(asset_id,deleted_at) VALUES (?,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE deleted_at=CURRENT_TIMESTAMP",firstAsset);
  assertEquals(1,json(call(null,"GET",direct,null),200).path("tracks").size());
  assertEquals(404,call(null,"GET","/api/public/artists/"+artist+"/songs/"+first+"/1/audio",null).statusCode());
  jdbc.update("UPDATE media_asset_annotation SET deleted_at=NULL WHERE asset_id=?",firstAsset);
  var different=wav();different[44]=1;
  String replacement=field(text(upload(owner,wa,different)),"id");
  jdbc.update("UPDATE artist_song_release SET asset_id=?,version=version+1 WHERE id=?",replacement,first);
  assertEquals(1,json(call(null,"GET",direct,null),200).path("tracks").size());
  jdbc.update("UPDATE artist_song_release SET show_on_profile=FALSE WHERE id=?",second);
  assertEquals(404,call(null,"GET",direct,null).statusCode());
  assertEquals(404,call(null,"GET",direct+"/cover/"+key,null).statusCode());
  assertEquals(200,albumForm(owner,write,2,false,java.util.List.of(first),null).statusCode());
  assertEquals(409,call(owner,"DELETE",write+"?version=2",null).statusCode());
  assertEquals(204,call(owner,"DELETE",write+"?version=3",null).statusCode());
  assertEquals(0,json(call(owner,"GET","/api/app/artist-albums",null),200).size());
 }

 private tools.jackson.databind.JsonNode json(HttpResponse<byte[]> response, int status) {
  assertEquals(status, response.statusCode(), text(response));
  return new tools.jackson.databind.ObjectMapper().readTree(response.body());
 }

 @Test void songVersionSharingRequiresOptInAndCountsActualPlayback() throws Exception {
  var owner = session();
  var other = session();
  String work = work(owner);
  String revision = lyrics(owner, work, "在这里听见");
  String api = "/api/app/works/" + work;
  assertEquals(200, call(owner, "POST", api + "/lyrics/" + revision + "/confirm", "{}").statusCode());
  byte[] first = wav(), second = wav();
  second[44] = 1;
  String assetA = field(text(upload(owner, work, first)), "id");
  String assetB = field(text(upload(owner, work, second)), "id");
  String stateA = api + "/shares/" + assetA, stateB = api + "/shares/" + assetB;
  assertEquals("PRIVATE", json(call(owner, "GET", stateA, null), 200).path("status").asString());
  assertEquals(401, call(null, "GET", stateA, null).statusCode());
  assertEquals(404, call(other, "GET", stateA, null).statusCode());

  String consentA = "{\"assetId\":\"" + assetA + "\",\"revisionId\":\"" + revision + "\",\"publicSong\":true}";
  String consentB = consentA.replace(assetA, assetB);
  var giftOnly = json(call(owner, "POST", api + "/shares", consentA.replace("true", "false")), 200);
  String giftPage = "/gift/" + giftOnly.path("token").asString();
  assertEquals("PRIVATE", json(call(owner, "GET", stateA, null), 200).path("status").asString());
  var grantA = json(call(owner, "POST", api + "/shares", consentA), 200);
  String idA = grantA.path("songId").asString();
  assertEquals("PRIVATE", json(call(owner, "GET", stateB, null), 200).path("status").asString());
  String idB = json(call(owner, "POST", api + "/shares", consentB), 200).path("songId").asString();
  assertNotEquals(idA, idB);
  String publicA = "/api/songs/" + idA;
  assertEquals(200, call(null, "HEAD", "/song/" + idA, null).statusCode());
  assertEquals(200, call(null, "GET", "/song/" + idA, null).statusCode());
  for (String range : java.util.List.of("bytes=0-1", "bytes=0-15", "bytes=16-")) {
   var read = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + publicA + "/audio")).header("Range",range).build();
   assertEquals(206, owner.client().send(read,HttpResponse.BodyHandlers.discarding()).statusCode());
  }
  var beforePlay = json(call(owner, "GET", stateA, null), 200);
  assertEquals("SHAREABLE", beforePlay.path("status").asString());
  assertEquals(idA, beforePlay.path("card").path("songId").asString());
  assertEquals(1, beforePlay.path("views").asLong());
  assertEquals(0, beforePlay.path("plays").asLong());
  assertEquals(204, call(null, "POST", publicA + "/play", null).statusCode());
  assertEquals(1, json(call(null, "GET", publicA + "/community", null), 200).path("plays").asLong());
  jdbc.update("UPDATE share_link SET created_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), grantA.path("id").asString());
  assertEquals(idA, json(call(owner, "POST", api + "/shares", consentA), 200).path("songId").asString());
  assertEquals(1, json(call(owner, "GET", stateA, null), 200).path("plays").asLong());
  assertEquals(1, json(call(null, "GET", publicA + "/community", null), 200).path("plays").asLong());
  assertEquals(0, json(call(null, "GET", "/api/songs/" + idB + "/community", null), 200).path("plays").asLong());

  assertEquals(404, call(other, "DELETE", stateA, null).statusCode());
  assertEquals(200, call(owner, "DELETE", stateA, null).statusCode());
  var revoked = json(call(owner, "GET", stateA, null), 200);
  assertEquals("REVOKED", revoked.path("status").asString());
  assertTrue(revoked.path("card").isNull());
  assertEquals(200, call(null, "GET", giftPage, null).statusCode());
  assertEquals(404, call(null, "GET", "/song/" + idA, null).statusCode());
  var revokedEnglish = call(null, "GET", "/song/" + idA + "?lang=en", null);
  assertEquals(404, revokedEnglish.statusCode());
  assertFalse(text(revokedEnglish).contains("og:title"));
  for (String path : java.util.List.of("/public", "/audio", "/cover", "/thumbnail.png"))
   assertEquals(404, call(null, "GET", publicA + path, null).statusCode(), path);
  assertEquals(404, call(null, "POST", publicA + "/play", null).statusCode());
  assertArrayEquals(second, call(null, "GET", "/api/songs/" + idB + "/audio", null).body());

  jdbc.update("UPDATE share_link SET expires_at=? WHERE public_song_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(5)),idB);
  assertEquals("EXPIRED", json(call(owner, "GET", stateB, null), 200).path("status").asString());
  assertEquals(404, call(null, "GET", "/api/songs/" + idB + "/audio", null).statusCode());
  var expiredEnglish = call(null, "GET", "/song/" + idB + "?lang=en", null);
  assertEquals(404, expiredEnglish.statusCode());
  assertFalse(text(expiredEnglish).contains("og:title"));
  assertEquals(404, call(null, "HEAD", "/song/" + idB + "/x-player?lang=en", null).statusCode());
  assertEquals(idB, json(call(owner, "POST", api + "/shares", consentB), 200).path("songId").asString());
  assertEquals(200, call(owner, "DELETE", api + "?acknowledgeCompleted=true", null).statusCode());
  assertEquals(404, call(null, "GET", "/song/" + idB, null).statusCode());
  assertEquals(404, call(null, "GET", "/api/songs/" + idB + "/audio", null).statusCode());
 }

 @Test void xCardUsesAnExplicitPlayerVariantAndTheExistingPublicGrant() throws Exception {
  var owner = session();
  String work = work(owner), revision = lyrics(owner, work, "听见我的歌");
  String api = "/api/app/works/" + work;
  assertEquals(200, call(owner, "POST", api + "/lyrics/" + revision + "/confirm", "{}").statusCode());
  String asset = field(text(upload(owner, work, wav())), "id");
  String consent = "{\"assetId\":\"" + asset + "\",\"revisionId\":\"" + revision + "\",\"publicSong\":true}";
  String id = json(call(owner, "POST", api + "/shares", consent), 200).path("songId").asString();
  String song = "/song/" + id, media = "/api/songs/" + id, status = api + "/shares/" + asset;
  String origin = "https://cheerfulmusic.com";

  var ordinary = call(null, "GET", song + "?lang=en", null);
  assertEquals(200, ordinary.statusCode());
  assertEquals("DENY", ordinary.headers().firstValue("X-Frame-Options").orElseThrow());
  assertTrue(text(ordinary).contains("name=\"twitter:card\" content=\"summary\""));
  assertFalse(text(ordinary).contains("name=\"twitter:player\""));

  var xCard = call(null, "GET", song + "?card=x&lang=en&tracking=example", null);
  assertEquals(200, xCard.statusCode());
  String markup = text(xCard);
  assertTrue(markup.contains("name=\"twitter:card\" content=\"player\""));
  assertTrue(markup.contains("name=\"twitter:player\" content=\"" + origin + song + "/x-player?lang=en\""));
  assertTrue(markup.contains("name=\"twitter:player:width\" content=\"480\""));
  assertTrue(markup.contains("name=\"twitter:player:height\" content=\"270\""));
  assertTrue(markup.contains("name=\"twitter:image\" content=\"" + origin + media + "/thumbnail.png?card=x\""));
  assertTrue(markup.contains("rel=\"canonical\" href=\"" + origin + song + "?lang=en&amp;card=x\""));
  assertTrue(markup.contains("data-wechat-url=\"" + origin + song + "\""));
  assertFalse(markup.contains("name=\"twitter:site\""));

  var thumbnail = call(null, "GET", media + "/thumbnail.png?card=x", null);
  assertEquals(200, thumbnail.statusCode());
  var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(thumbnail.body()));
  assertEquals(480, image.getWidth());
  assertEquals(270, image.getHeight());

  long visits = json(call(owner, "GET", status, null), 200).path("views").asLong();
  for (String method : java.util.List.of("GET", "HEAD")) {
   var embed = call(null, method, song + "/x-player?lang=en", null);
   assertEquals(200, embed.statusCode(), method);
   assertEquals("no-store", embed.headers().firstValue("Cache-Control").orElseThrow());
   assertTrue(embed.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors https://x.com"));
   assertTrue(embed.headers().firstValue("X-Frame-Options").isEmpty());
   if (method.equals("GET")) {
    String player = text(embed);
    assertTrue(player.contains("<html lang=\"en\">"));
    assertTrue(player.contains("controls playsinline preload=\"none\" src=\"" + media + "/audio\""));
    assertTrue(player.contains("data-play-url=\"" + media + "/play\""));
    assertTrue(player.contains("href=\"" + song + "?lang=en\""));
    assertFalse(player.contains("autoplay"));
   } else assertEquals(0, embed.body().length);
  }
  assertTrue(text(call(null, "GET", song + "/x-player", null)).contains("<html lang=\"zh-CN\">"));
  var afterEmbed = json(call(owner, "GET", status, null), 200);
  assertEquals(visits, afterEmbed.path("views").asLong());
  assertEquals(0, afterEmbed.path("plays").asLong());

  assertEquals(200, call(owner, "DELETE", status, null).statusCode());
  var revoked = call(null, "GET", song + "/x-player?lang=en", null);
  assertEquals(404, revoked.statusCode());
  assertEquals("DENY", revoked.headers().firstValue("X-Frame-Options").orElseThrow());
  assertEquals(404, call(null, "GET", media + "/thumbnail.png?card=x", null).statusCode());
 }

 @Test void shortSongLinksRenderTheExistingAuthorizedSong() throws Exception {
  var owner = session();
  String work = work(owner), revision = lyrics(owner, work, "给朋友的短链接");
  String api = "/api/app/works/" + work;
  assertEquals(200, call(owner, "POST", api + "/lyrics/" + revision + "/confirm", "{}").statusCode());
  String asset = field(text(upload(owner, work, wav())), "id");
  String consent = "{\"assetId\":\"" + asset + "\",\"revisionId\":\"" + revision + "\",\"publicSong\":true}";
  String id = json(call(owner, "POST", api + "/shares", consent), 200).path("songId").asString();
  assertEquals(id, json(call(owner, "POST", api + "/shares", consent), 200).path("songId").asString());
  String code = id.substring(0, 22), path = "/s/" + code, status = api + "/shares/" + asset;
  String url = "https://cheerfulmusic.com" + path;
  var initial = json(call(owner, "GET", status, null), 200);
  assertEquals(code, initial.path("card").path("shortCode").asString());
  assertEquals(id, initial.path("card").path("songId").asString());
  var head = call(null, "HEAD", path + "?card=x&lang=en", null);
  assertEquals(200, head.statusCode());
  assertEquals(0, head.body().length);
  assertEquals("no-store", head.headers().firstValue("Cache-Control").orElseThrow());
  var normal = call(null, "GET", path, null);
  assertEquals(200, normal.statusCode());
  assertEquals("DENY", normal.headers().firstValue("X-Frame-Options").orElseThrow());
  assertTrue(text(normal).contains("rel=\"canonical\" href=\"" + url + "\""));
  assertTrue(text(normal).contains("src=\"/api/songs/" + id + "/audio\""));
  assertTrue(text(normal).contains("name=\"twitter:card\" content=\"summary\""));
  var playerCard = call(null, "GET", path + "?card=x&lang=en&from=example", null);
  assertEquals(200, playerCard.statusCode());
  String markup = text(playerCard);
  assertTrue(markup.contains("rel=\"canonical\" href=\"" + url + "?lang=en&amp;card=x\""));
  assertTrue(markup.contains("property=\"og:url\" content=\"" + url + "?lang=en&amp;card=x\""));
  assertTrue(markup.contains("data-wechat-url=\"" + url + "\""));
  assertTrue(markup.contains("value=\"" + url + "?lang=en\""));
  assertTrue(markup.contains("name=\"twitter:card\" content=\"player\""));
  assertTrue(markup.contains("name=\"twitter:player\" content=\"https://cheerfulmusic.com/song/" + id + "/x-player?lang=en\""));
  assertEquals(initial.path("views").asLong() + 2, json(call(owner, "GET", status, null), 200).path("views").asLong());
  assertEquals(200, call(null, "HEAD", "/song/" + id, null).statusCode());
  assertEquals(404, call(null, "GET", "/s/" + "A".repeat(22), null).statusCode());

  jdbc.update("UPDATE share_link SET expires_at=? WHERE public_song_id=?",
    java.sql.Timestamp.from(Instant.now().minusSeconds(5)), id);
  assertEquals(404, call(null, "GET", path, null).statusCode());
  assertEquals(id, json(call(owner, "POST", api + "/shares", consent), 200).path("songId").asString());
  assertEquals(200, call(null, "HEAD", path, null).statusCode());
  assertEquals(200, call(owner, "DELETE", status, null).statusCode());
  assertEquals(404, call(null, "GET", path, null).statusCode());
 }

 @Test void ambiguityAcrossHistoricSharesDisablesTheShortLink() throws Exception {
  var owner = session();
  String work = work(owner), revision = lyrics(owner, work, "处理有歧义的短链接");
  String api = "/api/app/works/" + work;
  assertEquals(200, call(owner, "POST", api + "/lyrics/" + revision + "/confirm", "{}").statusCode());
  String asset = field(text(upload(owner, work, wav())), "id");
  String consent = "{\"assetId\":\"" + asset + "\",\"revisionId\":\"" + revision + "\",\"publicSong\":true}";
  var older = json(call(owner, "POST", api + "/shares", consent), 200);
  String id = json(call(owner, "POST", api + "/shares", consent), 200).path("songId").asString();
  String code = id.substring(0, 22);
  String conflict = code + (id.charAt(22) == 'A' ? 'B' : 'A') + id.substring(23);
  jdbc.update("UPDATE share_link SET public_song_id=?,active=FALSE WHERE id=?", conflict, older.path("id").asString());

  var status = json(call(owner, "GET", api + "/shares/" + asset, null), 200);
  assertEquals(id, status.path("card").path("songId").asString());
  var shortCode = status.path("card").path("shortCode");
  assertTrue(shortCode.isNull() || shortCode.isMissingNode());
  assertEquals(404, call(null, "GET", "/s/" + code, null).statusCode());
  assertEquals(200, call(null, "HEAD", "/song/" + id, null).statusCode());
 }

 @Test void uploadedCoverRemainsPrivateUntilSharedAndGiftRevocationClosesItsRoute()throws Exception{
  var a=session();var other=session();String w=work(a),r=lyrics(a,w,"中文与 English");call(a,"POST","/api/app/works/"+w+"/lyrics/"+r+"/confirm","{}");String audio=field(text(upload(a,w,wav())),"id");
  var pixels=new java.awt.image.BufferedImage(48,48,java.awt.image.BufferedImage.TYPE_INT_RGB);var encoded=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(pixels,"png",encoded);
  String boundary="CoverBoundary";byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"../../private.svg\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8),suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);byte[] image=encoded.toByteArray();var body=ByteBuffer.allocate(prefix.length+image.length+suffix.length).put(prefix).put(image).put(suffix).array();
  var uploaded=a.client().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/app/works/"+w+"/covers")).header("X-XSRF-TOKEN",a.csrf()).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,uploaded.statusCode(),text(uploaded));String cover=field(text(uploaded),"id");
  assertEquals(404,call(other,"GET","/api/app/works/"+w+"/covers/"+cover,null).statusCode());assertEquals(401,call(null,"GET","/api/app/works/"+w+"/covers/"+cover,null).statusCode());assertEquals(200,call(a,"PUT","/api/app/works/"+w+"/covers/selection","{\"coverId\":\""+cover+"\",\"expectedVersion\":0}").statusCode());
  String token=share(a,w,r,audio),publicPath="/api/public/gifts/"+token+"/cover";var publicCover=call(null,"GET",publicPath,null);assertEquals(200,publicCover.statusCode());assertEquals("image/jpeg",publicCover.headers().firstValue("Content-Type").orElse(""));assertEquals("nosniff",publicCover.headers().firstValue("X-Content-Type-Options").orElse(""));assertEquals(1024,javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(publicCover.body())).getWidth());
  String request = "{\"assetId\":\"" + audio + "\",\"revisionId\":\"" + r + "\",\"publicSong\":true}";
  var published = json(call(a, "POST", "/api/app/works/" + w + "/shares", request), 200);
  String songApi = "/api/songs/" + published.path("songId").asString();
  assertArrayEquals(publicCover.body(), call(null, "GET", songApi + "/cover", null).body());
  var thumbnail = call(null, "GET", songApi + "/thumbnail.png", null);
  assertEquals(200, thumbnail.statusCode());
  assertEquals(240, javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(thumbnail.body())).getWidth());

  jdbc.update("UPDATE cover_asset SET storage_key=? WHERE id=?", "local:" + UUID.randomUUID() + ".jpg", cover);
  assertEquals(404, call(null, "GET", songApi + "/cover", null).statusCode());
  assertEquals(404, call(null, "HEAD", songApi + "/cover", null).statusCode());
  assertEquals(404, call(null, "GET", publicPath, null).statusCode());
  var fallback = call(null, "GET", songApi + "/thumbnail.png", null);
  assertEquals(200, fallback.statusCode());
  assertEquals("image/png", fallback.headers().firstValue("Content-Type").orElse(""));
  assertArrayEquals(new org.springframework.core.io.ClassPathResource("static/brand/share-default-cover.png").getContentAsByteArray(), fallback.body());
  call(a,"DELETE","/api/app/works/"+w+"/shares",null);assertEquals(404,call(null,"GET",publicPath,null).statusCode());
  assertEquals(404, call(null, "GET", songApi + "/thumbnail.png", null).statusCode());
 }

 @Test void deletedVersionCannotCreateOrAdvertisePublicPreview() throws Exception {
  var owner = session();
  String work = work(owner), revision = lyrics(owner, work, "两首版本");
  String api = "/api/app/works/" + work;
  assertEquals(200, call(owner, "POST", api + "/lyrics/" + revision + "/confirm", "{}").statusCode());
  String firstAsset = json(upload(owner, work, wav()), 200).path("id").asString();
  byte[] second = wav();
  second[44] = 2;
  String asset = field(text(upload(owner, work, second)), "id");
  String selectionApi = api + "/audio/selection";
  long selectionVersion = json(call(owner, "GET", selectionApi, null), 200).path("version").asLong();
  String selectFirst = "{\"assetId\":\"" + firstAsset + "\",\"version\":" + selectionVersion + "}";
  assertEquals(200, call(owner, "PUT", selectionApi, selectFirst).statusCode());
  String consent = "{\"assetId\":\"" + asset + "\",\"revisionId\":\"" + revision + "\",\"publicSong\":true}";
  var created = json(call(owner, "POST", api + "/shares", consent), 200);
  String songId = created.path("songId").asString();
  assertEquals("SHAREABLE", json(call(owner, "GET", api + "/shares/" + asset, null), 200).path("status").asString());

  assertEquals(200, call(owner, "DELETE", api + "/audio/" + asset, null).statusCode());
  assertEquals(404, call(owner, "GET", api + "/shares/" + asset, null).statusCode());
  assertEquals(404, call(owner, "POST", api + "/shares", consent).statusCode());
  assertEquals(404, call(null, "GET", "/song/" + songId, null).statusCode());
  assertEquals(404, call(null, "GET", "/api/songs/" + songId + "/audio", null).statusCode());
  assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM share_link WHERE work_id=? AND asset_id=?", Integer.class, work, asset));
  assertArrayEquals(second, call(null, "GET", "/api/public/gifts/" + created.path("token").asString() + "/audio", null).body());
 }

 @Test void eachGeneratedCandidateSharesItsOwnAudioAndTheOriginalLyrics() throws Exception {
  var owner = session();
  String workId = work(owner);
  String revisionId = lyrics(owner, workId, "两首歌曲共用的歌词");
  assertEquals(200, call(owner, "POST", "/api/app/works/" + workId + "/lyrics/" + revisionId + "/confirm", "{}").statusCode());
  String taskId = UUID.randomUUID().toString();
  new MyBatisTaskRepository(tasks).insertOrRead(new Task(taskId, workId, revisionId, 0, UUID.randomUUID().toString(),
      "给你的歌", "两首歌曲共用的歌词", "民谣", "offline", GenerationStatus.SUCCEEDED, Instant.now()));
  byte[] original = wav(), alternate = wav();
  alternate[44] = 1;
  String primary = media.store(taskId, workId, new ByteArrayInputStream(original));
  String secondary = media.storeCandidate(taskId, workId, "second", null, new ByteArrayInputStream(alternate));
  jdbc.update("UPDATE generation_task SET asset_id=? WHERE id=?", primary, taskId);

  String sharePath = "/api/app/works/" + workId + "/shares";
  String secondaryRequest = "{\"assetId\":\"" + secondary + "\"}";
  assertEquals(404, call(session(), "POST", sharePath, secondaryRequest).statusCode());
  var secondaryShare = call(owner, "POST", sharePath, secondaryRequest);
  assertEquals(200, secondaryShare.statusCode(), text(secondaryShare));
  String secondaryGift = "/api/public/gifts/" + field(text(secondaryShare), "token");
  assertTrue(text(call(null, "GET", secondaryGift, null)).contains("两首歌曲共用的歌词"));
  assertArrayEquals(alternate, call(null, "GET", secondaryGift + "/audio", null).body());

  var primaryShare = call(owner, "POST", sharePath, "{\"assetId\":\"" + primary + "\"}");
  assertEquals(200, primaryShare.statusCode(), text(primaryShare));
  String primaryToken = field(text(primaryShare), "token");
  assertArrayEquals(original, call(null, "GET", "/api/public/gifts/" + primaryToken + "/audio", null).body());

  String workPath = "/api/app/works/" + workId;
  var renamed = call(owner, "PUT", workPath,
      draft.replace("故事", "新的歌曲名字").replace("}", ",\"expectedVersion\":0}"));
  assertEquals(200, renamed.statusCode(), text(renamed));
  assertEquals("新的歌曲名字", field(text(call(owner, "GET", workPath, null)), "title"));
  assertEquals("给你的歌", field(text(call(owner, "GET", workPath + "/lyrics/" + revisionId, null)), "title"));
  assertEquals("给你的歌", field(text(call(null, "GET", secondaryGift, null)), "title"));

  var renamedShare = call(owner, "POST", sharePath,
      "{\"assetId\":\"" + secondary + "\",\"workTitle\":\"新的歌曲名字\"}");
  assertEquals(200, renamedShare.statusCode(), text(renamedShare));
  String renamedGift = "/api/public/gifts/" + field(text(renamedShare), "token");
  assertEquals("新的歌曲名字", field(text(call(null, "GET", renamedGift, null)), "title"));
  assertArrayEquals(alternate, call(null, "GET", renamedGift + "/audio", null).body());
 }
 @Test void unconfirmedLyricsAndInvalidAudioCannotBecomeGift()throws Exception{
  var a=session();String w=work(a),r=lyrics(a,w,"还未确认");
  assertEquals(400,upload(a,w,"<script>not audio</script>".getBytes()).statusCode());assertEquals("[]",text(call(a,"GET","/api/app/works/"+w+"/audio",null)));
  String asset=field(text(upload(a,w,wav())),"id");assertEquals(409,call(a,"POST","/api/app/works/"+w+"/shares","{\"revisionId\":\""+r+"\",\"assetId\":\""+asset+"\"}").statusCode());
 }
 @Test void unavailableModelDoesNotCreateFakeQueuedTask()throws Exception{
  var a=session();String w=work(a),r=lyrics(a,w,"手动歌词");call(a,"POST","/api/app/works/"+w+"/lyrics/"+r+"/confirm","{}");
  assertEquals(503,call(a,"POST","/api/app/works/"+w+"/generations","{\"revisionId\":\""+r+"\",\"requestKey\":\""+java.util.UUID.randomUUID()+"\"}").statusCode());
  assertEquals("[]",text(call(a,"GET","/api/app/works/"+w+"/generations",null)));
 }
 @Test void publishedGiftIsSnapshotAndOldLyricsCannotFollowEditedWork()throws Exception{
  var a=session();String w=work(a),r=lyrics(a,w,"原始歌词");call(a,"POST","/api/app/works/"+w+"/lyrics/"+r+"/confirm","{}");String asset=field(text(upload(a,w,wav())),"id");String token=share(a,w,r,asset);
  assertEquals(200,call(a,"PUT","/api/app/works/"+w,draft.replace("原始祝福","改后的祝福").replace("}",",\"expectedVersion\":0}")).statusCode());
  String publicText=text(call(null,"GET","/api/public/gifts/"+token,null));assertTrue(publicText.contains("原始祝福"));assertFalse(publicText.contains("改后的祝福"));
  assertEquals(409,call(a,"POST","/api/app/works/"+w+"/shares","{\"revisionId\":\""+r+"\",\"assetId\":\""+asset+"\"}").statusCode());
 }
}
