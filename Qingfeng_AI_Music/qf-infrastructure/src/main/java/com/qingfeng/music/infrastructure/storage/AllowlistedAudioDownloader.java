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
        try{uri=URI.create(value);}catch(RuntimeException e){throw new DownloadFailure(Problem.INVALID_URL);}
        try{return download(uri);}
        catch(DownloadFailure failure){throw failure;}
        catch(SocketTimeoutException failure){throw new DownloadFailure(Problem.CONNECT_TIMEOUT,failure);}
        catch(IOException failure){throw new DownloadFailure(Problem.IO,failure);}
    }
    private InputStream download(URI uri)throws IOException{
        for(int redirects=0;redirects<=3;redirects++){
            validate(uri);
            var connection=(HttpURLConnection)uri.toURL().openConnection();
            connection.setConnectTimeout(5000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);
            try{
                int status=connection.getResponseCode();
                if(status>=300&&status<400){
                    String location=connection.getHeaderField("Location");
                    connection.disconnect();
                    if(location==null)throw new DownloadFailure(Problem.INVALID_URL);
                    try{uri=uri.resolve(location);}catch(IllegalArgumentException e){throw new DownloadFailure(Problem.INVALID_URL);}
                    continue;
                }
                if(status==401||status==403||status==404||status==410)throw new DownloadFailure(Problem.LINK_EXPIRED);
                if(status!=200)throw new DownloadFailure(Problem.HTTP_STATUS);
                long expected=connection.getContentLengthLong();
                if(expected>20L*1024*1024)throw new DownloadFailure(Problem.SIZE_LIMIT);
                return new FilterInputStream(connection.getInputStream()){
                    private final long deadline=System.nanoTime()+java.time.Duration.ofSeconds(40).toNanos();
                    private long received;
                    @Override public int read()throws IOException{
                        byte[] one=new byte[1];
                        return read(one,0,1)==-1?-1:Byte.toUnsignedInt(one[0]);
                    }
                    @Override public int read(byte[] b,int off,int len)throws IOException{
                        if(System.nanoTime()>deadline)throw new DownloadFailure(Problem.DEADLINE);
                        int count;
                        try{count=super.read(b,off,len);}
                        catch(SocketTimeoutException failure){throw new DownloadFailure(Problem.READ_TIMEOUT,failure);}
                        catch(IOException failure){throw new DownloadFailure(Problem.IO,failure);}
                        if(count>0)received+=count;
                        if(count<0&&expected>=0&&received!=expected)throw new DownloadFailure(Problem.INCOMPLETE_BODY);
                        return count;
                    }
                    public void close()throws IOException{try{super.close();}finally{connection.disconnect();}}
                };
            }catch(IOException|RuntimeException e){connection.disconnect();throw e;}
        }
        throw new DownloadFailure(Problem.INVALID_URL);
    }
    public void validate(URI uri)throws IOException{
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null
            ||uri.getUserInfo()!=null||uri.getFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new DownloadFailure(Problem.INVALID_URL);
        if(!hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))&&!providerUrls.contains(uri))throw new DownloadFailure(Problem.URL_NOT_REGISTERED);
        for(var address:InetAddress.getAllByName(uri.getHost())){
            byte[] bytes=address.getAddress();
            if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress()
                ||(bytes.length==16&&(bytes[0]&0xfe)==0xfc)||(bytes.length==4&&(bytes[0]&255)==100&&(bytes[1]&255)>=64&&(bytes[1]&255)<=127))throw new IOException("不允许访问内部音频地址");
        }
    }
}
