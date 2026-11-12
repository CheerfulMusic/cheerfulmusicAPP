package com.qingfeng.music.server;

import com.qingfeng.music.server.agent.retrieval.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opt in only to the isolated local test container, never a configured production service. */
@EnabledIfEnvironmentVariable(named="QF_TEST_QDRANT_URL",matches="http://127\\.0\\.0\\.1:16333")
class AgentSemanticQdrantIntegrationTest {
    @Test void realVectorStoreEnforcesTenantFilterAndDeletesOnlyOwnedPoint() throws Exception {
        String base=System.getenv("QF_TEST_QDRANT_URL"),collection="test_agent_"+UUID.randomUUID().toString().replace("-","");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        URI url=URI.create(base+"/collections/"+collection);
        var create=client.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}" )).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,create.statusCode(),create.body());
        var embedding=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        embedding.createContext("/embeddings",exchange->{exchange.getRequestBody().readAllBytes();byte[] bytes="{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});embedding.start();
        try {
            var gateway=new HttpSemanticGateway(true,"http://127.0.0.1:"+embedding.getAddress().getPort()+"/embeddings","local-fixture","",3,base,collection,"");
            var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:qdrant-source-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            var db=new JdbcTemplate(ds);
            db.execute("CREATE TABLE music_work(id VARCHAR(36) PRIMARY KEY,owner_id VARCHAR(36),version BIGINT,title VARCHAR(100),story TEXT,message TEXT,deleted_at TIMESTAMP)");
            db.execute("CREATE TABLE lyric_revision(id VARCHAR(36),work_id VARCHAR(36),work_version BIGINT,title VARCHAR(100),lyrics TEXT,confirmed BOOLEAN,created_at TIMESTAMP)");
            db.update("INSERT INTO music_work VALUES('work-a','alice',1,'A','旧故事','',NULL)");
            var source=new SemanticSource(db);var old=source.load("alice","work-a").orElseThrow();gateway.index(old);
            gateway.index(new SemanticSource.Document("bob","work-b",1,"r-b","B","测试内容B","hash-b"));
            var found=gateway.search("alice","同样的向量",10);assertEquals(1,found.size());assertEquals("work-a",found.get(0).work());
            db.update("UPDATE music_work SET version=2,story='当前的新故事' WHERE id='work-a'");
            var fresh=source.load("alice","work-a").orElseThrow();gateway.index(fresh);
            gateway.index(old); // Stale A writes after B, then crashes before reaching queue.complete.
            var versions=gateway.search("alice","同样的向量",30);
            assertEquals(2,versions.size());assertTrue(versions.stream().anyMatch(hit->hit.hash().equals(fresh.hash())));
            var queue=new JdbcSemanticIndexQueue(db,new TransactionTemplate(new DataSourceTransactionManager(ds)),false);
            var result=new SemanticRetrievalActions(source,gateway,queue).execute("alice","c","search_memory",Map.of("query","故事"),"","",null);
            assertEquals(1,result.items().size());assertEquals(2L,result.items().get(0).data().get("version"));assertEquals(fresh.hash(),result.items().get(0).data().get("sourceHash"));
            gateway.remove("alice","work-a");assertTrue(gateway.search("alice","查询",10).isEmpty());
            assertEquals("work-b",gateway.search("bob","查询",10).get(0).work());
        } finally {
            embedding.stop(0);
            var deleted=client.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).DELETE().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,deleted.statusCode(),deleted.body());
        }
    }
}
