package com.qingfeng.music.work.api;
import com.qingfeng.music.common.exception.DomainException;

/** 站内稳定业务值；供应商的 m/f 仅在适配器内转换。 */
public enum VocalGender {
    AUTO, MALE, FEMALE;
    public static String normalize(String value){
        if(value==null)return AUTO.name();
        try{return valueOf(value).name();}
        catch(IllegalArgumentException invalid){throw new DomainException("INVALID_VOCAL_GENDER","请选择自动、男声或女声");}
    }
}
