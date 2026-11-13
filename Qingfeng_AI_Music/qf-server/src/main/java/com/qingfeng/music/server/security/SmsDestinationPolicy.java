package com.qingfeng.music.server.security;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.util.*;

/** Provider routing and an explicit, default-deny destination allowlist. */
public final class SmsDestinationPolicy {
    private static final PhoneNumberUtil PHONES=PhoneNumberUtil.getInstance();
    private final boolean phoneEnabled, internationalLoginEnabled, bridgeEnabled, mainlandEnabled, internationalEnabled;
    private final Set<String> internationalRegions;

    public SmsDestinationPolicy(boolean phoneEnabled, boolean internationalLoginEnabled, boolean bridgeEnabled,
                                boolean mainlandEnabled, boolean internationalEnabled, String allowedRegions){
        this.phoneEnabled=phoneEnabled;this.internationalLoginEnabled=internationalLoginEnabled;
        this.bridgeEnabled=bridgeEnabled;this.mainlandEnabled=mainlandEnabled;this.internationalEnabled=internationalEnabled;
        Set<String> parsed=new TreeSet<>();
        if(allowedRegions!=null&&!allowedRegions.isBlank())for(String raw:allowedRegions.split(",",-1)){
            String region=raw.trim();
            if(!region.matches("[A-Z]{2}")||"CN".equals(region)||!PHONES.getSupportedRegions().contains(region))
                throw new IllegalStateException("Invalid SMS destination region configuration");
            parsed.add(region);
        }
        internationalRegions=Set.copyOf(parsed);
    }

    /** Input must be canonical E.164 digits without a plus sign. */
    public Optional<String> region(String digits){
        return validMobile(digits).flatMap(number->{
            String region=PHONES.getRegionCodeForNumber(number);
            if(region==null||"001".equals(region))return Optional.empty();
            // A few Isle of Man +44 mobiles are also valid under broad GB metadata,
            // and getRegionCodeForNumber reports GB. Reject those overlapping +44
            // allocations rather than letting a GB allowlist include another territory.
            if(number.getCountryCode()==44&&"GB".equals(region)
                &&List.of("JE","GG","IM").stream().anyMatch(other->PHONES.isValidNumberForRegion(number,other)))
                return Optional.empty();
            return Optional.of(region);
        });
    }

    /** Distinguishes a valid but ambiguous destination from a malformed number. */
    public boolean isValidMobile(String digits){return validMobile(digits).isPresent();}

    private static Optional<PhoneNumber> validMobile(String digits){
        if(digits==null||!digits.matches("[1-9][0-9]{7,14}"))return Optional.empty();
        try{
            PhoneNumber number=PHONES.parse("+"+digits,"ZZ");
            return PHONES.isValidNumber(number)&&PHONES.getNumberType(number)==PhoneNumberUtil.PhoneNumberType.MOBILE
                &&("+"+digits).equals(PHONES.format(number,PhoneNumberUtil.PhoneNumberFormat.E164))
                ?Optional.of(number):Optional.empty();
        }catch(NumberParseException invalid){return Optional.empty();}
    }

    public boolean allows(String digits){
        String region=region(digits).orElse(null);
        if(region==null)return false;
        return phoneEnabled&&bridgeEnabled&&(mainlandEnabled&&"CN".equals(region)
            ||internationalLoginEnabled&&internationalEnabled&&internationalRegions.contains(region));
    }

    public List<String> availableRegions(boolean phoneEnabled,boolean internationalLoginEnabled){
        if(!phoneEnabled||!this.phoneEnabled||!bridgeEnabled)return List.of();
        List<String> regions=new ArrayList<>();
        if(mainlandEnabled)regions.add("CN");
        if(internationalEnabled&&this.internationalLoginEnabled&&internationalLoginEnabled)regions.addAll(internationalRegions.stream().sorted().toList());
        return List.copyOf(regions);
    }
}
