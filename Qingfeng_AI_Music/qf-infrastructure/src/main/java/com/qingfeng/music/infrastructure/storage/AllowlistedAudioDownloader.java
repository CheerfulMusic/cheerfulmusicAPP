package com.qingfeng.music.infrastructure.storage;

import com.qingfeng.music.generation.application.port.AudioDownloader;
import java.io.*;
import java.net.*;
import java.util.*;

/** 允许配置的HTTPS域名或已认证供应商登记的精确结果URL；每次跳转重新校验，拒绝内部地址。 */
public final class AllowlistedAudioDownloader implements AudioDownloader {
    private final Set<String> hosts;
    private final ProviderAudioUrls providerUrls;
    public AllowlistedAudioDownloader(Set<String> hosts){this(hosts,new ProviderAudioUrls());}
    public AllowlistedAudioDownloader(Set<String> hosts,ProviderAudioUrls providerUrls){this.hosts=Set.copyOf(hosts);this.providerUrls=providerUrls;}
    public InputStream open(String value)throws IOException{
        URI uri;
        try{uri=URI.create(value);}catch(RuntimeException e){throw new IOException("无效音频地址");}
        for(int redirects=0;redirects<=3;redirects++){
            validate(uri);
            var connection=(HttpURLConnection)uri.toURL().openConnection();
            connection.setConnectTimeout(5000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);
            try{
                int status=connection.getResponseCode();
                if(status>=300&&status<400){String location=connection.getHeaderField("Location");connection.disconnect();if(location==null)throw new IOException("音频重定向缺少地址");uri=uri.resolve(location);continue;}
                if(status!=200)throw new IOException("音频下载暂不可用");
                if(connection.getContentLengthLong()>20L*1024*1024)throw new IOException("音频超过大小限制");
                return new FilterInputStream(connection.getInputStream()){
                    private final long deadline=System.nanoTime()+java.time.Duration.ofSeconds(40).toNanos();
                    public int read(byte[] b,int off,int len)throws IOException{if(System.nanoTime()>deadline)throw new IOException("音频下载超时");return super.read(b,off,len);}
                    public void close()throws IOException{try{super.close();}finally{connection.disconnect();}}
                };
            }catch(IOException|RuntimeException e){connection.disconnect();throw e;}
        }
        throw new IOException("音频重定向次数过多");
    }
    public void validate(URI uri)throws IOException{
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||(!hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))&&!providerUrls.contains(uri))
            ||uri.getUserInfo()!=null||uri.getFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new IOException("音频域名不在允许列表");
        for(var address:InetAddress.getAllByName(uri.getHost())){
            byte[] bytes=address.getAddress();
            if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress()
                ||(bytes.length==16&&(bytes[0]&0xfe)==0xfc)||(bytes.length==4&&(bytes[0]&255)==100&&(bytes[1]&255)>=64&&(bytes[1]&255)<=127))throw new IOException("不允许访问内部音频地址");
        }
    }
}
