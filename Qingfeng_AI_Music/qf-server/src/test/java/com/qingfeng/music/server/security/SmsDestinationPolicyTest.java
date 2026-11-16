package com.qingfeng.music.server.security;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SmsDestinationPolicyTest {
    @Test void noInternationalDestinationIsAllowedByDefault(){
        var policy=new SmsDestinationPolicy(true,true,true,true,true,"");
        assertTrue(policy.allows("8613800000000"));
        assertFalse(policy.allows("447400123456"));
        assertEquals(List.of("CN"),policy.availableRegions(true,true));
    }

    @Test void sharedFortyFourCallingCodeMustResolveToOneApprovedTerritory(){
        var policy=new SmsDestinationPolicy(true,true,true,false,true,"GB, JE");
        assertEquals("GB",policy.region("447400123456").orElseThrow());
        assertEquals("JE",policy.region("447797712345").orElseThrow());
        assertTrue(policy.allows("447400123456"));
        assertTrue(policy.allows("447797712345"));
        assertEquals("GG",policy.region("447781123456").orElseThrow());
        assertFalse(policy.allows("447781123456"));
        // libphonenumber's preferred region for this Isle of Man example is GB,
        // although the same number is valid for both GB and IM. Reject ambiguity.
        assertTrue(policy.region("447924123456").isEmpty());
        assertTrue(policy.isValidMobile("447924123456"));
        assertFalse(policy.allows("447924123456"));
        assertFalse(policy.allows("447700900123")); // Reserved fictional number.
        assertFalse(policy.isValidMobile("447700900123"));
        assertFalse(policy.allows("442079460000")); // Landline.
        assertEquals(List.of("GB","JE"),policy.availableRegions(true,true));
        assertEquals(List.of(),policy.availableRegions(true,false));
    }

    @Test void laterRegionsRequireAnExplicitConfigurationAndEnabledRoute(){
        var policy=new SmsDestinationPolicy(true,true,true,false,true,"GB,IT,MY,AU,FR");
        for(String number:List.of("393123456789","60123456789","61412345678","33612345678"))
            assertTrue(policy.allows(number),number);
        assertFalse(new SmsDestinationPolicy(true,false,true,false,true,"GB").allows("447400123456"));
        assertFalse(new SmsDestinationPolicy(false,true,true,false,true,"GB").allows("447400123456"));
        assertFalse(new SmsDestinationPolicy(true,true,true,false,false,"GB,IT").allows("447400123456"));
        assertFalse(new SmsDestinationPolicy(true,true,false,false,true,"GB").allows("447400123456"));
        assertThrows(IllegalStateException.class,()->new SmsDestinationPolicy(true,true,true,false,true,"GB,ZZ"));
        assertThrows(IllegalStateException.class,()->new SmsDestinationPolicy(true,true,true,false,true,"GB,CN"));
        assertThrows(IllegalStateException.class,()->new SmsDestinationPolicy(true,true,true,false,true,"GB,"));
    }
}
