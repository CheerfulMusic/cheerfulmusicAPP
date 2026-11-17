package com.qingfeng.music.server.mv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
class VideoImagesTest {
 @TempDir Path root;
 @Test void imageOwnershipAndFormatAreEnforced()throws Exception{
  var store=new VideoImages(root);var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",bytes);
  String id=store.save("owner","work",new ByteArrayInputStream(bytes.toByteArray()));assertTrue(java.nio.file.Files.isRegularFile(store.resolve("owner","work",id)));
  assertThrows(RuntimeException.class,()->store.resolve("other","work",id));assertThrows(RuntimeException.class,()->store.resolve("owner","other",id));assertThrows(RuntimeException.class,()->store.resolve("owner","work","../../secret"));
  assertThrows(RuntimeException.class,()->store.save("owner","work",new ByteArrayInputStream("not an image".getBytes())));
 }
}
