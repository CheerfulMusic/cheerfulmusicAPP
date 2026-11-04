package com.qingfeng.music.infrastructure.provider;

import java.net.URI;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** 歌曲归档和歌词时间轴共用同一套KIE结果结构、资源身份匹配。 */
final class KieAudioResults {
    private KieAudioResults(){}
    static java.util.List<JsonNode> groups(JsonNode result){
        var groups=new java.util.ArrayList<JsonNode>();
        // KIE统一任务实际返回resultObject.lyricsData；同时保留已有扁平返回兼容。
        // 只读取明确的成品音频字段，不将stream_audio_url当成已完成文件。
        for(var container:java.util.List.of(result,result.path("resultObject")))
            for(String field:new String[]{"sunoData","data","tracks","lyricsData"}){
                var tracks=container.path(field);if(tracks.isArray())groups.add(tracks);
            }
        return groups;
    }
    static boolean sameAudio(String left,String right){
        try{var a=URI.create(left);var b=URI.create(right);return "https".equals(a.getScheme())&&Objects.equals(a.getScheme(),b.getScheme())&&Objects.equals(a.getHost(),b.getHost())&&Objects.equals(a.getRawPath(),b.getRawPath())&&a.getPort()==b.getPort()&&identityQuery(a).equals(identityQuery(b));}catch(RuntimeException invalid){return false;}
    }
    private static String identityQuery(URI uri){
        if(uri.getRawQuery()==null)return "";
        return java.util.Arrays.stream(uri.getRawQuery().split("&")).filter(part->{String key=part.split("=",2)[0].toLowerCase(java.util.Locale.ROOT);return !key.startsWith("x-amz-")&&!key.startsWith("x-oss-")&&!java.util.Set.of("expires","signature","ossaccesskeyid","securitytoken","policy","key-pair-id").contains(key);}).sorted().collect(java.util.stream.Collectors.joining("&"));
    }
}
