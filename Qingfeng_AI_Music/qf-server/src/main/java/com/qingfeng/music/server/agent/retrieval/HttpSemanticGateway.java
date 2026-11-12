package com.qingfeng.music.server.agent.retrieval;

import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** OpenAI-compatible embedding wire format + Qdrant REST. Endpoints are operator configuration only. */
public final class HttpSemanticGateway implements SemanticGateway {
    private final boolean enabled;private final URI embedding,qdrant;private final String model,embeddingKey,qdrantKey,collection,schema;
    private final int dimensions;private final HttpClient http;private final ObjectMapper json=new ObjectMapper();
    public HttpSemanticGateway(boolean enabled,String embeddingUrl,String model,String embeddingKey,int dimensions,String qdrantUrl,String collection,String qdrantKey) {
        this.enabled=enabled;this.model=model==null?"":model;this.embeddingKey=embeddingKey==null?"":embeddingKey;this.qdrantKey=qdrantKey==null?"":qdrantKey;
        this.dimensions=dimensions;this.collection=collection==null?"":collection;
        embedding=endpoint(embeddingUrl);qdrant=endpoint(qdrantUrl);
        schema="private-works-v1:"+this.model+":"+dimensions;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private URI endpoint(String value) {
        if(value==null||value.isBlank())return null;
        URI uri=URI.create(value);
        boolean loopback=Set.of("localhost","127.0.0.1","[::1]").contains(Objects.toString(uri.getHost(),""));
        if(uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getQuery()!=null||uri.getHost()==null||!("https".equals(uri.getScheme())||loopback&&"http".equals(uri.getScheme())))
            throw new IllegalArgumentException("Semantic endpoint must use HTTPS (HTTP allowed only on loopback)");
        return uri;
    }
    @Override public boolean configured(){return enabled&&embedding!=null&&qdrant!=null&&!model.isBlank()&&dimensions>0&&dimensions<=8192&&collection.matches("[A-Za-z0-9_-]{1,80}");}
    private void ready(){if(!configured())throw new IllegalStateException("SEMANTIC_NOT_CONFIGURED");}
    private tools.jackson.databind.JsonNode request(URI uri,String method,Object body,String key,boolean vector) {
        ready();
        try {
            var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
            if(!key.isBlank())builder.header(vector?"api-key":"Authorization",vector?key:"Bearer "+key);
            var response=http.send(builder.method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()/100!=2||response.body().length()>2_000_000)throw new IllegalStateException("SEMANTIC_PROVIDER_RESPONSE");
            return json.readTree(response.body());
        } catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("SEMANTIC_INTERRUPTED");}
        catch(java.io.IOException error){throw new IllegalStateException("SEMANTIC_PROVIDER_UNAVAILABLE");}
    }
    private List<Double> embed(String text){
        // Explicit bounded corpus; no chats, profile, orders, credentials, or arbitrary linked documents.
        String bounded=text.substring(0,Math.min(text.length(),8000));
        var node=request(embedding,"POST",Map.of("model",model,"input",bounded,"encoding_format","float"),embeddingKey,false).path("data").path(0).path("embedding");
        if(!node.isArray()||node.size()!=dimensions)throw new IllegalStateException("SEMANTIC_DIMENSION_MISMATCH");
        var values=new ArrayList<Double>();for(var value:node){double n=value.asDouble(Double.NaN);if(!Double.isFinite(n))throw new IllegalStateException("SEMANTIC_INVALID_VECTOR");values.add(n);}return values;
    }
    private URI points(String suffix){return URI.create(qdrant.toString().replaceAll("/+$","")+"/collections/"+collection+"/points"+suffix);}
    private String pointId(SemanticSource.Document document){
        // Immutable content identity: a worker that resumes after losing its lease cannot overwrite a newer vector,
        // even if it crashes immediately after the remote write and never reaches the acknowledgement repair.
        return UUID.nameUUIDFromBytes((document.owner()+":"+document.work()+":"+document.hash()+":"+schema).getBytes(StandardCharsets.UTF_8)).toString();
    }
    @Override public void index(SemanticSource.Document d){
        ready();var payload=Map.of("owner_id",d.owner(),"work_id",d.work(),"visibility","PRIVATE","work_version",d.version(),"revision_id",d.revision(),"source_hash",d.hash(),"deleted",false,"index_schema",schema);
        request(points("?wait=true"),"PUT",Map.of("points",List.of(Map.of("id",pointId(d),"vector",embed(d.text()),"payload",payload))),qdrantKey,true);
    }
    @Override public void remove(String owner,String work){
        ready();
        request(points("/delete?wait=true"),"POST",Map.of("filter",Map.of("must",List.of(match("owner_id",owner),match("work_id",work)))),qdrantKey,true);
    }
    private Map<String,Object> match(String key,Object value){return Map.of("key",key,"match",Map.of("value",value));}
    @Override public List<Hit> search(String owner,String query,int limit){
        ready();var filter=Map.of("must",List.of(match("owner_id",owner),match("visibility","PRIVATE"),match("deleted",false),match("index_schema",schema)));
        var result=request(points("/search"),"POST",Map.of("vector",embed(query),"filter",filter,"limit",Math.min(30,Math.max(1,limit)),"with_payload",true,"with_vector",false),qdrantKey,true).path("result");
        if(!result.isArray())throw new IllegalStateException("SEMANTIC_INVALID_RESPONSE");
        var hits=new ArrayList<Hit>();for(var row:result){var p=row.path("payload");
            if(!schema.equals(p.path("index_schema").asText()))continue;
            hits.add(new Hit(p.path("owner_id").asText(),p.path("work_id").asText(),p.path("visibility").asText(),p.path("work_version").asLong(-1),p.path("revision_id").asText(),p.path("source_hash").asText(),p.path("deleted").asBoolean(true),row.path("score").asDouble(0)));
        }return hits;
    }
}
