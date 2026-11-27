package com.qingfeng.music.server;

import com.aliyun.oss.OSS;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.sun.net.httpserver.HttpServer;
import com.aliyun.oss.model.*;
import com.qingfeng.music.infrastructure.storage.*;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.media.application.MediaRepository;
import com.qingfeng.music.media.application.MediaService;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.server.download.DownloadEntitlements;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.InetSocketAddress;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StorageBoundaryTest {
    @TempDir Path root;
    byte[] wav(){var b=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);return b.array();}
    @Test void unlockProbeRequestsOnlyOneByteFromTheCloudOverHttp()throws Exception{
        List<String> ranges=new ArrayList<>();
        List<String> requests=new ArrayList<>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI()+" Range="+exchange.getRequestHeaders().getFirst("Range"));
            exchange.getResponseHeaders().set("Connection","close");
            if("HEAD".equals(exchange.getRequestMethod())){exchange.getResponseHeaders().set("Content-Length","6730414");exchange.sendResponseHeaders(200,-1);exchange.close();return;}
            ranges.add(exchange.getRequestHeaders().getFirst("Range"));
            if(!"bytes=0-0".equals(exchange.getRequestHeaders().getFirst("Range"))){exchange.sendResponseHeaders(400,-1);exchange.close();return;}
            exchange.getResponseHeaders().set("Content-Range","bytes 0-0/6730414");
            exchange.sendResponseHeaders(206,1);
            try(var output=exchange.getResponseBody()){output.write(42);}
        });
        server.start();
        var config=new ClientBuilderConfiguration();config.setMaxErrorRetry(0);config.setSocketTimeout(1000);config.setConnectionTimeout(1000);
        OSS client=new OSSClientBuilder().build("http://127.0.0.1:"+server.getAddress().getPort(),"test-access","test-secret",config);
        try{
            var local=new LocalMediaFileStore(root);
            var cloud=new OssMediaFileStore(client,"qingfeng-test","qingfeng/audio/",local);
            var route=new RoutingMediaFileStore(local,cloud,true);
            var repository=mock(MediaRepository.class);
            var asset=new MediaApi.Asset("asset","work","GENERATED","audio/mpeg",6_730_414,"sha",java.time.Instant.now());
            when(repository.find("work","asset")).thenReturn(Optional.of(new MediaRepository.Stored(asset,"oss:7bb302ab-b803-4bf6-b507-7ad96fdf90ad.mp3")));
            var media=new MediaService(mock(WorkApi.class),repository,route,40L*1024*1024);
            var db=mock(JdbcTemplate.class);var ledger=mock(CreditsLedger.class);
            when(db.queryForObject(anyString(),eq(Integer.class),any(),any(),any(),any())).thenReturn(0);
            try{new DownloadEntitlements(db,ledger).unlock("owner","work",media.open("owner","work","asset"));}
            catch(RuntimeException failure){throw new AssertionError("Storage requests: "+requests,failure);}
            verify(ledger).debit(eq("owner"),eq(15L),startsWith("download-v1:"));
            assertEquals(List.of("bytes=0-0"),ranges);
            var saved=local.save(new ByteArrayInputStream(wav()));
            assertTrue(route.readable("local:"+saved.key()));
            assertEquals(1,ranges.size(),"legacy local probes must not contact OSS");
        }finally{client.shutdown();server.stop(0);}
    }
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
    @Test void transientCloudUploadReusesTheStagedFileAndObjectKey()throws Exception{
        List<String> attempts=new ArrayList<>();
        OSS client=(OSS)Proxy.newProxyInstance(OSS.class.getClassLoader(),new Class[]{OSS.class},(proxy,method,args)->{
            assertEquals("putObject",method.getName());
            var request=(PutObjectRequest)args[0];
            attempts.add(request.getKey());
            assertArrayEquals(wav(),request.getInputStream().readAllBytes());
            if(attempts.size()==1)throw new ClientException("mock timeout","SocketTimeout","mock");
            return new PutObjectResult();
        });
        var cloud=new OssMediaFileStore(client,"qingfeng-test","qingfeng/audio/",new LocalMediaFileStore(root));
        cloud.save(new ByteArrayInputStream(wav()));
        assertEquals(2,attempts.size());
        assertEquals(attempts.get(0),attempts.get(1));
        try(var files=Files.list(root)){assertEquals(0,files.count());}
    }
    @Test void cloudPermissionFailureDoesNotRetryThePut()throws Exception{
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        OSS client=(OSS)Proxy.newProxyInstance(OSS.class.getClassLoader(),new Class[]{OSS.class},(proxy,method,args)->{
            attempts.incrementAndGet();
            throw new OSSException("mock access denied","AccessDenied","mock","mock",null,null,null);
        });
        var cloud=new OssMediaFileStore(client,"qingfeng-test","qingfeng/audio/",new LocalMediaFileStore(root));
        assertThrows(com.qingfeng.music.media.application.MediaFileStore.UploadFailure.class,()->cloud.save(new ByteArrayInputStream(wav())));
        assertEquals(1,attempts.get());
    }
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
