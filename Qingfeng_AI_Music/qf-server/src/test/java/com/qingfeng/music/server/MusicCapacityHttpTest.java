package com.qingfeng.music.server;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.work.api.WorkApi;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="qf.capacity", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.address=127.0.0.1",
    "spring.datasource.url=jdbc:h2:mem:capacity_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.usage.music.daily-global=-1",
    "spring.sql.init.schema-locations=classpath:db/test/audio_version_annotations.sql,classpath:db/optional/music_admission_permits.sql",
    "qingfeng.credits.enabled=true", "qingfeng.generation.enabled=true"
})
@ActiveProfiles({"test","capacity-5000"})
@Import(KieMusicCapacityIntegrationTest.Offline.class)
class MusicCapacityHttpTest {
    @org.springframework.test.context.DynamicPropertySource
    static void isolatedDatabase(org.springframework.test.context.DynamicPropertyRegistry properties){
        String port=System.getProperty("qf.capacity.mysqlPort");
        if(port==null)return;
        if(!port.matches("57[0-9]{3}"))throw new IllegalArgumentException("Dedicated test port required");
        String schema=System.getProperty("qf.capacity.schema","qf_capacity_http");
        if(!schema.matches("qf_capacity_http(?:_[0-9]+)?"))throw new IllegalArgumentException("Dedicated schema required");
        String url="jdbc:mysql://127.0.0.1:"+port+"/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        var isolated=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,"root",""));
        assertEquals(Integer.parseInt(port),isolated.queryForObject("SELECT @@port",Integer.class));
        assertEquals("127.0.0.1",isolated.queryForObject("SELECT @@bind_address",String.class));
        assertEquals(schema,isolated.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(0,isolated.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        if(Boolean.getBoolean("qf.capacity.fenced")){
            properties.add("spring.flyway.target",()->"53");
            properties.add("qingfeng.credits.writer-fence.enabled",()->"true");
            properties.add("qingfeng.credits.writer-fence.expected-database",()->schema);
            properties.add("qingfeng.credits.writer-fence.expected-port",()->port);
            properties.add("spring.sql.init.schema-locations",()->"classpath:db/test/audio_version_annotations.sql,classpath:db/optional/music_admission_permits.sql,classpath:db/test/music_capacity_activate_empty_ledger.sql");
        }
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.username",()->"root");
        properties.add("spring.datasource.password",()->"");
    }
    @LocalServerPort int port;
    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired LyricsApi lyrics;
    @Autowired CreditsLedger credits;
    @Autowired JdbcTemplate db;
    @Autowired KieMusicCapacityIntegrationTest.Provider provider;
    private final HttpClient client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    record User(String work,String cookie,String csrf,String body){}
    private User fixture(){
        var session=identity.create();
        String owner=session.creatorId();
        credits.credit(owner,1000,"capacity-"+owner);
        var work=works.create(owner,new WorkApi.Draft("朋友","旅行","故事","祝福","民谣","歌曲"));
        var revision=lyrics.save(owner,work.id(),0,"容量验证","已确认歌词");
        lyrics.confirm(owner,work.id(),revision.id());
        String csrf=UUID.randomUUID().toString();
        return new User(work.id(),"QF_SESSION="+session.token()+"; XSRF-TOKEN="+csrf,csrf,
            "{\"revisionId\":\""+revision.id()+"\",\"requestKey\":\""+UUID.randomUUID()+"\"}");
    }
    private HttpRequest request(User user,boolean write,int i){
        String path="/api/app/works";
        if(write || i%3!=0) path+="/"+user.work();
        if(write || i%3==2) path+="/generations";
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .timeout(Duration.ofSeconds(30)).header("Cookie",user.cookie());
        if(write) b.header("X-XSRF-TOKEN",user.csrf()).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(user.body()));
        return b.build();
    }
    // Hold all sockets open before the first request, so connection reuse cannot mask the peak.
    private void simultaneousConnections(List<User> users)throws Exception{
        var sockets=new ArrayList<java.net.Socket>(users.size());
        try{
            for(int i=0;i<users.size();i++){
                var socket=new java.net.Socket();
                sockets.add(socket);
                socket.connect(new java.net.InetSocketAddress("127.0.0.1",port),10000);
                socket.setSoTimeout(30000);
            }
            long start=System.nanoTime();
            for(int i=0;i<sockets.size();i++){
                var user=users.get(i);
                String get="GET /api/app/works/"+user.work()+" HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: "+user.cookie()+"\r\nConnection: close\r\n\r\n";
                sockets.get(i).getOutputStream().write(get.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            for(int i=0;i<sockets.size();i++){
                String reply=new String(sockets.get(i).getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(reply.startsWith("HTTP/1.1 200 ")&&reply.contains(users.get(i).work()),"Failed socket "+i);
            }
            System.out.printf("CAPACITY simultaneous connections=%d completed=%d elapsed=%.2fs%n",
                sockets.size(),users.size(),(System.nanoTime()-start)/1e9);
        }finally{for(var socket:sockets)socket.close();}
    }
    // Measure from the scheduled arrival, including backlog on either side of the socket.
    private void run(List<User> users,boolean write,int count,int rate,int threshold)throws Exception{
        var workers=Executors.newFixedThreadPool(64);
        long[] times=new long[count];
        var done=new CountDownLatch(count);
        var failures=new java.util.concurrent.atomic.AtomicInteger();
        var first=new java.util.concurrent.atomic.AtomicReference<String>();
        long start=System.nanoTime();
        try{
            for(int n=0;n<count;n++){
                final int i=n;
                long due=start+i*1_000_000_000L/rate;
                while(System.nanoTime()<due) LockSupport.parkNanos(due-System.nanoTime());
                workers.execute(()->{
                    try{
                        var r=client.send(request(users.get(i%users.size()),write,i),HttpResponse.BodyHandlers.ofString());
                        if(r.statusCode()!=200 || !r.headers().firstValue("Content-Type").orElse("").contains("application/json")) throw new IllegalStateException(r.statusCode()+" "+r.body());
                    }catch(Exception e){failures.incrementAndGet();first.compareAndSet(null,e.toString());}
                    finally{times[i]=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-due);done.countDown();}
                });
            }
            assertTrue(done.await(2,TimeUnit.MINUTES));
            Arrays.sort(times);
            long p95=times[(int)Math.ceil(count*0.95)-1];
            System.out.printf("CAPACITY write=%s sessions=%d requests=%d rate=%d elapsed=%.2fs p95=%dms p99=%dms failures=%d%n",
                write,users.size(),count,rate,(System.nanoTime()-start)/1e9,p95,times[(int)Math.ceil(count*.99)-1],failures.get());
            assertEquals(0,failures.get(),first.get());
            assertTrue(p95<=threshold,"p95="+p95+"ms");
        }finally{workers.shutdownNow();}
    }
    @Test void steadyReadsAndBurstAdmission()throws Exception{
        int users=5000;
        int seconds=Integer.getInteger("qf.capacity.seconds",1800);
        var sessions=new ArrayList<User>();
        for(int i=0;i<users;i++)sessions.add(fixture());
        simultaneousConnections(sessions);
        run(sessions,false,500,100,2000);
        run(sessions,false,seconds*500,500,500);
        run(sessions,false,10000,1000,500);
        run(sessions,true,users,500,1000);
        assertEquals(users,db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE status='QUEUED'",Integer.class));
        run(sessions,true,users,500,1000);
        assertEquals(users,db.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='MUSIC'",Integer.class));
        assertEquals(users,db.queryForObject("SELECT COUNT(*) FROM generation_task",Integer.class));
        assertEquals(users,db.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='DEBIT'",Integer.class));
        assertEquals(users,db.queryForObject("SELECT COUNT(DISTINCT owner_id) FROM credit_ledger WHERE entry_type='DEBIT'",Integer.class));
        assertEquals(0,provider.submissions.get());
        var extra=fixture();
        var denied=client.send(request(extra,true,0),HttpResponse.BodyHandlers.ofString());
        assertEquals(429,denied.statusCode(),denied.body());
        assertTrue(denied.body().contains("GLOBAL_GENERATION_BUSY"),denied.body());
        var first=sessions.get(0);
        String task=db.queryForObject("SELECT id FROM generation_task WHERE work_id=?",String.class,first.work());
        var cancel=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/app/works/"+first.work()+"/generations/"+task+"/cancel"))
            .header("Cookie",first.cookie()).header("X-XSRF-TOKEN",first.csrf()).POST(HttpRequest.BodyPublishers.noBody()).build();
        var cancelled=client.send(cancel,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,cancelled.statusCode(),cancelled.body());
        var accepted=client.send(request(extra,true,0),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,accepted.statusCode(),accepted.body());
        assertEquals(users,db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE status='QUEUED'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND'",Integer.class));
    }
}
