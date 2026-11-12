package com.qingfeng.music.server.download;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.media.api.SharedMediaAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Paid gift access survives share revocation, but only for assets actually shared with this account. */
@Service @Profile({"mysql", "test"})
public class SharedDownloadGrants {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final SharedMediaAccess media;
    private final DownloadEntitlements downloads;
    public SharedDownloadGrants(JdbcTemplate db,TransactionTemplate tx,SharedMediaAccess media,DownloadEntitlements downloads){this.db=db;this.tx=tx;this.media=media;this.downloads=downloads;}
    public record Item(String id,String title,String workId,String assetId){}
    private record Share(String work,String asset,String title){}
    public List<Item> list(String owner){return db.query("SELECT id,title,work_id,asset_id FROM shared_download_entitlement WHERE owner_id=? ORDER BY created_at DESC,id",(r,n)->new Item(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),owner);}
    public MediaApi.Content content(String owner,String id){
        var rows=db.query("SELECT work_id,asset_id FROM shared_download_entitlement WHERE owner_id=? AND id=?",(r,n)->new String[]{r.getString(1),r.getString(2)},owner,id);
        if(rows.isEmpty())throw unavailable();
        return media.content(rows.get(0)[0],rows.get(0)[1]);
    }
    public MediaApi.Content contentForToken(String owner,String token){
        var ids=db.query("SELECT e.id FROM share_link s JOIN shared_download_entitlement e ON e.work_id=s.work_id AND e.asset_id=s.asset_id WHERE s.token_hash=? AND e.owner_id=?",(r,n)->r.getString(1),hash(token),owner);
        if(ids.isEmpty())throw unavailable();return content(owner,ids.get(0));
    }
    public boolean assetUnlocked(String owner,String token){
        Integer n=db.queryForObject("SELECT COUNT(*) FROM share_link s JOIN shared_download_entitlement e ON e.work_id=s.work_id AND e.asset_id=s.asset_id WHERE s.token_hash=? AND e.owner_id=?",Integer.class,hash(token),owner);
        return n!=null&&n>0;
    }
    public long price(String owner,String token){
        var shares=find(token,false);if(shares.isEmpty())throw unavailable();
        return downloads.unlocked(owner,shares.get(0).work())?0:DownloadEntitlements.PRICE;
    }
    public void unlock(String owner,String token){
        tx.executeWithoutResult(status->{
            // Creator lock first, matching credit ledger ordering; link lock serializes revocation.
            if(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner).isEmpty())throw unavailable();
            var shares=find(token,true);if(shares.isEmpty())throw unavailable();Share share=shares.get(0);
            MediaApi.Content content=media.content(share.work(),share.asset());
            // Ledger debit and durable entitlement participate in this same transaction.
            downloads.unlock(owner,share.work(),content);
            Integer present=db.queryForObject("SELECT COUNT(*) FROM shared_download_entitlement WHERE owner_id=? AND work_id=? AND asset_id=?",Integer.class,owner,share.work(),share.asset());
            if(present==null||present==0)db.update("INSERT INTO shared_download_entitlement(id,owner_id,work_id,asset_id,title,created_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),owner,share.work(),share.asset(),share.title());
        });
    }
    private List<Share> find(String token,boolean lock){
        String sql="SELECT s.work_id,s.asset_id,s.title FROM share_link s JOIN music_work w ON w.id=s.work_id WHERE s.token_hash=? AND s.active=TRUE AND s.expires_at>CURRENT_TIMESTAMP AND w.deleted_at IS NULL"+(lock?" FOR UPDATE":"");
        return db.query(sql,(r,n)->new Share(r.getString(1),r.getString(2),r.getString(3)),hash(token));
    }
    private static String hash(String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw unavailable();
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static DomainException unavailable(){return new DomainException("SHARE_NOT_FOUND","这份礼物不存在、已过期或已被撤销");}
}
