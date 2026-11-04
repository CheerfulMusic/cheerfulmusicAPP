package com.qingfeng.music.infrastructure.persistence.identity;

import com.qingfeng.music.identity.application.IdentityRepository;
import java.time.Instant;
import java.util.Optional;

public class MyBatisIdentityRepository implements IdentityRepository {
    private final IdentityMapper mapper;
    public MyBatisIdentityRepository(IdentityMapper mapper) { this.mapper = mapper; }
    public void create(String id, String hash, Instant now, Instant expires) { mapper.insertCreator(id, now); mapper.insertSession(hash,id,expires); }
    public Optional<String> findCreator(String hash, Instant now) { return Optional.ofNullable(mapper.find(hash, now)); }
    public void setRecovery(String creator,String hash){mapper.recovery(creator,hash);}
    public boolean hasRecovery(String creator){return mapper.hasRecovery(creator)>0;}
    public Optional<String> lockRecovery(String hash){return Optional.ofNullable(mapper.lockRecovery(hash));}
    public void addSession(String creator,String hash,Instant expires){mapper.insertSession(hash,creator,expires);}
    public boolean lockCreator(String creator){return mapper.lockCreator(creator)!=null;}
    public Optional<String> googleCreator(String hash){return Optional.ofNullable(mapper.googleCreator(hash));}
    public Optional<com.qingfeng.music.identity.api.IdentityApi.GoogleIdentity> googleIdentity(String creator){return Optional.ofNullable(mapper.googleIdentity(creator));}
    public void bindGoogle(String creator,String hash,String email,String name,Instant now){mapper.bindGoogle(creator,hash,email,name,now);}
    public void updateGoogle(String hash,String email,String name,Instant now){mapper.updateGoogle(hash,email,name,now);}
    public boolean hasContent(String creator){return mapper.contentCount(creator)>0;}
    public void deleteSession(String hash){mapper.deleteSession(hash);}
}
