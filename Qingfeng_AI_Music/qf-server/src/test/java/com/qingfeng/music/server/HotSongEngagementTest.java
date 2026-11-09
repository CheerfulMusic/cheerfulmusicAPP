package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.hotsong.HotSongService;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:hot_songs;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class HotSongEngagementTest {
 @Autowired HotSongService songs;@Autowired IdentityApi identities;@Autowired JdbcTemplate jdbc;

 @BeforeEach void reset(){jdbc.update("DELETE FROM hot_song_reaction");jdbc.update("DELETE FROM hot_song_metric");}

 private String creator(boolean loggedIn){
  String id=identities.create().creatorId();
  if(loggedIn)jdbc.update("INSERT INTO creator_google_identity(subject_hash,creator_id,email,display_name,created_at,updated_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          String.format("%-64s",UUID.randomUUID().toString().replace("-","")).replace(' ','0'),id,"fan@example.com","听众");
  return id;
 }

 @Test void playsAreCountedPerStartAndUnknownTracksAreRejected(){
  assertEquals(0,songs.stat("love").plays());
  assertEquals(1,songs.play("love").plays());
  assertEquals(2,songs.play("love").plays());
  assertEquals(0,songs.stat("family").plays());
  assertEquals(HotSongService.TRACKS.size(),songs.stats().size());
  assertTrue(songs.stats().stream().allMatch(stat->HotSongService.TRACKS.contains(stat.trackId())));
  var missing=assertThrows(DomainException.class,()->songs.play("not-a-track"));
  assertEquals("HOT_SONG_NOT_FOUND",missing.code());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM hot_song_metric",Integer.class));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM hot_song_metric WHERE track_id='not-a-track'",Integer.class));
 }

 @Test void likingRequiresABoundLoginIdentity(){
  var guest=creator(false);
  var rejected=assertThrows(ResponseStatusException.class,()->songs.react(guest,"love","like",true));
  assertEquals(HttpStatus.FORBIDDEN,rejected.getStatusCode());
  assertEquals(0,songs.stat("love").likes());

  var listener=creator(true);
  var liked=songs.react(listener,"love","like",true);
  assertTrue(liked.liked());assertFalse(liked.favorited());
  assertEquals(1,liked.stat().likes());
 }

 @Test void repeatedClicksCountOnceAndRemovalNeverGoesBelowZero(){
  var listener=creator(true);
  assertEquals(1,songs.react(listener,"travel","like",true).stat().likes());
  assertEquals(1,songs.react(listener,"travel","like",true).stat().likes());
  assertTrue(songs.reactions(listener).liked().contains("travel"));

  var unliked=songs.react(listener,"travel","like",false);
  assertFalse(unliked.liked());assertEquals(0,unliked.stat().likes());
  assertEquals(0,songs.react(listener,"travel","like",false).stat().likes());

  var favorited=songs.react(listener,"travel","favorite",true);
  assertTrue(favorited.favorited());assertEquals(1,favorited.stat().favorites());
  assertEquals(0,favorited.stat().likes());
  assertEquals(0,songs.react(listener,"travel","favorite",false).stat().favorites());
 }

 @Test void unsupportedReactionKindIsRejectedWithoutTouchingCounters(){
  var listener=creator(true);
  var invalid=assertThrows(DomainException.class,()->songs.react(listener,"love","share",true));
  assertEquals("INVALID_REACTION_KIND",invalid.code());
  assertEquals(0,songs.stat("love").likes());
 }
}
