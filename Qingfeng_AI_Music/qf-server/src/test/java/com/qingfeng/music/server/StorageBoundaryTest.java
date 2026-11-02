package com.qingfeng.music.server;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import com.qingfeng.music.infrastructure.storage.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StorageBoundaryTest {
    @TempDir Path root;
    byte[] wav(){var b=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);return b.array();}
    @Test void cloudAdapterKeepsObjectsPrivateAndLegacyLocalFilesReadable()throws Exception{
        Map<String,byte[]> objects=new HashMap<>();List<Object> acls=new ArrayList<>();
        OSS client=(OSS)Proxy.newProxyInstance(OSS.class.getClassLoader(),new Class[]{OSS.class},(proxy,method,args)->switch(method.getName()){
            case "putObject"->{var request=(PutObjectRequest)args[0];objects.put(request.getKey(),request.getInputStream().readAllBytes());acls.add(request.getMetadata().getRawMetadata().get("x-oss-object-acl"));yield new PutObjectResult();}
            case "getObject"->{var result=new OSSObject();result.setObjectContent(new ByteArrayInputStream(objects.get((String)args[1])));yield result;}
            case "doesObjectExist"->objects.containsKey((String)args[1]);
            case "deleteObject"->{objects.remove((String)args[1]);yield null;}
            default->throw new AssertionError("不允许未预期的OSS调用:"+method.getName());
        });
        var local=new LocalMediaFileStore(root.resolve("local"));var original=local.save(new ByteArrayInputStream(wav()));
        var cloud=new OssMediaFileStore(client,"qingfeng-test","qingfeng/audio/",new LocalMediaFileStore(root.resolve("staging")));
        var route=new RoutingMediaFileStore(local,cloud,true);var saved=route.save(new ByteArrayInputStream(wav()));assertTrue(saved.key().startsWith("oss:"));assertEquals(List.of("private"),acls);
        try(var input=route.open(original.key())){assertArrayEquals(wav(),input.readAllBytes());}try(var input=route.open(saved.key())){assertArrayEquals(wav(),input.readAllBytes());}
        assertTrue(route.exists(saved.key()));route.discard(saved.key());assertFalse(route.exists(saved.key()));try(var files=Files.list(root.resolve("staging"))){assertEquals(0,files.count());}
    }
    @Test void downloaderRejectsPrivateUnapprovedAndNonHttpsLocations(){var downloader=new AllowlistedAudioDownloader(Set.of("localhost"));for(String value:List.of("http://localhost/a","https://localhost/a","https://unapproved.example/a","https://name:password@localhost/a","file:///tmp/a"))assertThrows(IOException.class,()->downloader.validate(URI.create(value)));}
    @Test void providerApprovalIsForOneExactUrlAndStillRejectsInternalAddresses(){
        var urls=new ProviderAudioUrls();urls.approve("https://media.example/song.mp3?signature=one");
        assertTrue(urls.contains(URI.create("https://media.example/song.mp3?signature=one")));
        assertFalse(urls.contains(URI.create("https://media.example/other.mp3?signature=one")));
        assertFalse(urls.contains(URI.create("https://media.example/song.mp3?signature=two")));
        assertThrows(IllegalArgumentException.class,()->urls.approve("http://media.example/song.mp3"));
        urls.approve("https://127.0.0.1/song.mp3");
        assertThrows(IOException.class,()->new AllowlistedAudioDownloader(Set.of(),urls).validate(URI.create("https://127.0.0.1/song.mp3")));
    }
    @Test void fileStoreRejectsTraversalAndCleansInvalidUpload()throws Exception{var store=new LocalMediaFileStore(root);assertThrows(RuntimeException.class,()->store.open("../../secret.wav"));assertThrows(RuntimeException.class,()->store.save(new ByteArrayInputStream("not an audio".getBytes())));try(var files=Files.list(root)){assertEquals(0,files.count());}}
}
