package com.qingfeng.music.server.query;

import java.util.*;
import java.util.regex.Pattern;

/** An excerpt copied from an owned source. Vector scores rank documents, not individual lines. */
public record WorkSearchEvidence(String source,String excerpt,String revisionId,int revisionNumber,String revisionTitle) {
    private static final Pattern TERMS=Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}]+");

    public record Material(String title,String story,String message,String lyricTitle,String lyrics,String revisionId,int revisionNumber) {
        public WorkSearchEvidence literal(String query){
            if(query==null||query.isBlank())return null;
            if(query.equalsIgnoreCase(lyricTitle))return evidence(field("lyricTitle",lyricTitle),0,lyricTitle.length());
            if(query.equalsIgnoreCase(title))return evidence(field("title",title),0,title.length());
            for(var field:fields()){
                int offset=indexOf(field.text(),query);
                if(offset>=0)return evidence(field,offset,query.length());
            }
            return null;
        }
        public WorkSearchEvidence semantic(String query){
            var literal=literal(query);
            if(literal!=null)return literal;
            var terms=new LinkedHashSet<String>();
            var words=TERMS.matcher(query.toLowerCase(Locale.ROOT));
            while(words.find()){
                String word=words.group();
                int[] chars=word.codePoints().toArray();
                if(chars.length>1&&Character.UnicodeScript.of(chars[0])==Character.UnicodeScript.HAN){
                    for(int i=1;i<chars.length;i++)terms.add(new String(chars,i-1,2));
                }else terms.add(word);
            }
            WorkSearchEvidence best=null;
            int bestScore=-1;
            // With no lexical overlap, prefer a real story passage as a representative excerpt.
            for(var field:List.of(field("story",story),field("lyrics",lyrics),field("message",message),field("title",title))){
                for(int start=0;start<field.text().length();){
                    int stop=field.text().indexOf('\n',start);
                    if(stop<0)stop=field.text().length();
                    // Compare bounded passages even when a story is one long paragraph.
                    for(int i=start;i<stop;i+=180){
                        String passage=field.text().substring(i,Math.min(i+200,stop));
                        if(passage.isBlank())continue;
                        int score=0,first=-1;
                        String folded=passage.toLowerCase(Locale.ROOT);
                        for(String term:terms){
                            int found=folded.indexOf(term);
                            if(found>=0){score++;if(first<0||found<first)first=found;}
                        }
                        if(score>bestScore){bestScore=score;best=evidence(field,i+Math.min(passage.length()-1,Math.max(first,0)),1);}
                    }
                    start=stop+1;
                }
            }
            return best;
        }
        private List<Field> fields(){
            return List.of(field("lyrics",lyrics),field("lyricTitle",lyricTitle),field("title",title),field("story",story),field("message",message));
        }
        private WorkSearchEvidence evidence(Field field,int offset,int length){
            boolean lyric=field.key().equals("lyrics")||field.key().equals("lyricTitle");
            return new WorkSearchEvidence(field.key(),excerptAt(field.text(),offset,length,field.key().equals("lyrics")),
                lyric?revisionId:null,lyric?revisionNumber:0,lyric?lyricTitle:null);
        }
    }
    private record Field(String key,String text){}
    private static Field field(String key,String text){return new Field(key,text==null?"":text);}
    private static int indexOf(String text,String term){
        if(term==null||term.isEmpty())return -1;
        for(int i=0;i<=text.length()-term.length();i++)if(text.regionMatches(true,i,term,0,term.length()))return i;
        return -1;
    }
    private static String excerptAt(String text,int offset,int length,boolean lyrics){
        int start=text.lastIndexOf('\n',offset-1)+1,end=text.indexOf('\n',offset+length);
        if(end<0)end=text.length();
        if(lyrics){
            int next=end;
            while(next<text.length()&&Character.isWhitespace(text.charAt(next)))next++;
            if(next<text.length()){
                int nextEnd=text.indexOf('\n',next);
                end=nextEnd<0?text.length():nextEnd;
            }else{
                int previous=start;
                while(previous>0&&Character.isWhitespace(text.charAt(previous-1)))previous--;
                if(previous>0)start=text.lastIndexOf('\n',previous-1)+1;
            }
        }
        int left=end-start<=320?start:Math.max(start,offset-100),right=Math.min(end,Math.max(left+320,offset+length));
        if(left>0&&Character.isLowSurrogate(text.charAt(left)))left--;
        if(right<text.length()&&Character.isLowSurrogate(text.charAt(right)))right++;
        return (left>start?"…":"")+text.substring(left,right).strip()+(right<end?"…":"");
    }
    public void addTo(Map<String,Object> data){
        data.put("matchSource",source);data.put("excerpt",excerpt);data.put("untrustedSource",true);
        if(revisionId!=null&&!revisionId.isBlank()){
            data.put("revisionId",revisionId);data.put("lyricVersion",revisionNumber);
        }
    }
}
