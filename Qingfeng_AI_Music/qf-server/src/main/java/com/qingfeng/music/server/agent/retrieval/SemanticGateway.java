package com.qingfeng.music.server.agent.retrieval;

import java.util.List;

public interface SemanticGateway {
    record Hit(String owner,String work,String visibility,long version,String revision,String hash,boolean deleted,double score) {}
    boolean configured();
    void index(SemanticSource.Document document);
    void remove(String owner,String work);
    List<Hit> search(String owner,String query,int limit);
}
