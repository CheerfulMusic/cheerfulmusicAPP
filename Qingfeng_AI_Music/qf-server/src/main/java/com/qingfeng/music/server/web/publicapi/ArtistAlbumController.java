package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.server.artist.ArtistAlbumService;
import com.qingfeng.music.server.artist.ArtistAlbumService.Album;
import com.qingfeng.music.server.security.CreatorPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile({"mysql","test"})
public class ArtistAlbumController {
    private final ArtistAlbumService albums;
    public ArtistAlbumController(ArtistAlbumService albums) { this.albums=albums; }

    @GetMapping("/api/app/artist-albums")
    public ResponseEntity<List<Album>> mine(@AuthenticationPrincipal CreatorPrincipal owner) {
        return fresh(albums.mine(owner.id()));
    }
    @PostMapping(value="/api/app/artist-albums/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Album save(@AuthenticationPrincipal CreatorPrincipal owner, @PathVariable UUID id,
            @RequestParam long version, @RequestParam String title, @RequestParam(defaultValue="") String description,
            @RequestParam boolean published, @RequestParam(required=false) List<String> tracks,
            @RequestParam(required=false) List<Long> versions,
            @RequestParam(defaultValue="false") boolean removeCover, @RequestParam(required=false) MultipartFile cover) {
        return albums.save(owner.id(),id.toString(),new ArtistAlbumService.Edit(version,title,description,published,
                tracks==null?List.of():tracks,versions==null?List.of():versions,removeCover,cover));
    }
    @DeleteMapping("/api/app/artist-albums/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal CreatorPrincipal owner, @PathVariable UUID id, @RequestParam long version) {
        albums.delete(owner.id(),id.toString(),version);
    }
    @GetMapping("/api/app/artist-albums/{id}/cover/{key}")
    public ResponseEntity<byte[]> mineCover(@AuthenticationPrincipal CreatorPrincipal owner, @PathVariable UUID id,@PathVariable UUID key) {
        return image(albums.ownerCover(owner.id(),id.toString(),key.toString()));
    }
    @GetMapping("/api/public/artists/{artist}/albums")
    public ResponseEntity<List<Album>> list(@PathVariable UUID artist) {
        return fresh(albums.published(artist.toString()));
    }
    @GetMapping("/api/public/artists/{artist}/albums/{id}")
    public ResponseEntity<Album> album(@PathVariable UUID artist,@PathVariable UUID id) {
        return fresh(albums.publicAlbum(artist.toString(),id.toString()));
    }
    @GetMapping("/api/public/artists/{artist}/albums/{id}/cover/{key}")
    public ResponseEntity<byte[]> cover(@PathVariable UUID artist,@PathVariable UUID id,@PathVariable UUID key) {
        return image(albums.publicCover(artist.toString(),id.toString(),key.toString()));
    }

    private static <T> ResponseEntity<T> fresh(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private static ResponseEntity<byte[]> image(byte[] bytes) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(bytes);
    }
}
