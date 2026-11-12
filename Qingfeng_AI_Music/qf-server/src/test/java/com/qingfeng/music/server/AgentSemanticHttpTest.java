package com.qingfeng.music.server;

import com.qingfeng.music.server.agent.retrieval.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentSemanticHttpTest {
    @Test void embeddingAndQdrantWireProtocolUsesPrivateOwnerFilterAndMetadataOnly() throws Exception {
        var json=new ObjectMapper();var bodies=Collections.synchronizedList(new ArrayList<String>());
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);bodies.add(exchange.getRequestURI()+" "+request);
            String response=exchange.getRequestURI().getPath().equals("/embeddings")?"{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}":"{\"result\":[],\"status\":\"ok\"}";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort();
            var gateway=new HttpSemanticGateway(true,url+"/embeddings","test-embed","",3,url,"private_works","");
            assertTrue(gateway.configured());
            gateway.index(new SemanticSource.Document("owner","work",2,"revision","title","private story","hash"));
            gateway.search("owner","妈妈",5);gateway.remove("owner","work");
            assertEquals(5,bodies.size());
            String points=bodies.stream().filter(b->b.startsWith("/collections/private_works/points?wait=true")).findFirst().orElseThrow();
            assertFalse(points.contains("private story"));assertTrue(points.contains("source_hash"));
            String search=bodies.stream().filter(b->b.startsWith("/collections/private_works/points/search ")).findFirst().orElseThrow();
            var body=json.readTree(search.substring(search.indexOf(' ')+1));
            assertEquals(4,body.path("filter").path("must").size());assertTrue(search.contains("owner_id"));assertTrue(search.contains("PRIVATE"));assertTrue(search.contains("index_schema"));
            assertThrows(IllegalStateException.class,()->new HttpSemanticGateway(true,url+"/embeddings","test-embed","",4,url,"private_works","").search("owner","query",1));
        } finally {server.stop(0);}
    }
    @Test void noDefaultExternalEndpointAndNoPlainHttpNonLoopback(){
        assertFalse(new HttpSemanticGateway(true,"","","",0,"","","").configured());
        assertThrows(IllegalArgumentException.class,()->new HttpSemanticGateway(true,"http://example.invalid/embeddings","model","",3,"","", ""));
    }
}
