package com.qingfeng.music.cover.application;
public interface ImageEditProvider {
 boolean configured();
 String model();
 String submit(byte[] jpeg,String prompt,String model);
 default String submit(byte[] jpeg, String prompt, String model, java.net.URI callback) {
  return submit(jpeg, prompt, model);
 }
 Result query(String task,String model);
 record Result(String status,String url){}
 class Rejected extends RuntimeException{public Rejected(){super("封面服务明确拒绝请求");}}
}
