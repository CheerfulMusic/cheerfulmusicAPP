package com.qingfeng.music.cover.application;
public interface ImageEditProvider {
 boolean configured();
 String model();
 String submit(byte[] jpeg,String prompt,String model);
 Result query(String task,String model);
 record Result(String status,String url){}
 class Rejected extends RuntimeException{public Rejected(){super("封面服务明确拒绝请求");}}
}
