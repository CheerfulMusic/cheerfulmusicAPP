package com.qingfeng.music.server.avatar;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.storage.CoverImageStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class AvatarServiceTest {
    @TempDir Path directory;
    private byte[] image() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(80,60,BufferedImage.TYPE_INT_RGB),"png",out);
        return out.toByteArray();
    }
    private AvatarService service() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE creator (id VARCHAR(36) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE creator_avatar (creator_id VARCHAR(36) PRIMARY KEY REFERENCES creator(id),storage_key VARCHAR(512) NOT NULL,byte_size BIGINT NOT NULL,version VARCHAR(36) NOT NULL)");
        jdbc.update("INSERT INTO creator (id) VALUES (?), (?)","owner-a","owner-b");
        return new AvatarService(jdbc,new CoverImageStore(directory,null,"","avatars/",false),new DataSourceTransactionManager(ds));
    }
    @Test void storesDecodedPrivateImagesPerOwnerAndReturnsVersionedUrl() throws Exception {
        var avatars = service();
        assertNull(avatars.current("owner-a").version());
        var result = avatars.save("owner-a",new ByteArrayInputStream(image()));
        assertEquals("/api/app/avatar/image",result.imageUrl()); assertNotNull(result.version());
        assertNull(avatars.current("owner-b").imageUrl());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> avatars.image("owner-b"));
        try (var in = avatars.open(avatars.image("owner-a"))) {
            var decoded = ImageIO.read(in); assertEquals(1024,decoded.getWidth()); assertEquals(1024,decoded.getHeight());
        }
        assertEquals(1,java.nio.file.Files.list(directory).count());
    }
    @Test void invalidImageAndStaleGenerationCannotReplaceLatestUpload() throws Exception {
        var avatars = service();
        var first = avatars.save("owner-a",new ByteArrayInputStream(image()));
        assertThrows(DomainException.class,() -> avatars.save("owner-a",new ByteArrayInputStream(new byte[]{1,2,3})));
        assertEquals(first.version(),avatars.current("owner-a").version());
        var newer = avatars.save("owner-a",new ByteArrayInputStream(image()));
        assertTrue(avatars.saveIfVersion("owner-a",first.version(),new ByteArrayInputStream(image())).isEmpty());
        assertEquals(newer.version(),avatars.current("owner-a").version());
        assertEquals(1,java.nio.file.Files.list(directory).count());
        assertTrue(avatars.saveIfVersion("owner-b",null,new ByteArrayInputStream(image())).isPresent());
    }
    @Test void uploadRejectsAnAccountChangeBeforeReadingOrSaving() {
        var avatars = org.mockito.Mockito.mock(AvatarService.class);
        var controller = new com.qingfeng.music.server.web.app.AvatarController(avatars);
        var file = new org.springframework.mock.web.MockMultipartFile("file","avatar.png","image/png",new byte[]{1});
        var failure = assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> controller.upload(new com.qingfeng.music.server.security.CreatorPrincipal("owner-b"),file,"owner-a"));
        assertEquals(409,failure.getStatusCode().value());
        org.mockito.Mockito.verifyNoInteractions(avatars);
    }
}
