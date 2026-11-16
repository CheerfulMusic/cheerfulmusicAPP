package com.qingfeng.music.server.config;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinancialClockStartupGuardTest {
    private static final Instant NOW=Instant.parse("2026-11-13T06:30:00Z");
    private static final BigDecimal EPOCH=BigDecimal.valueOf(NOW.getEpochSecond());
    @Test void explicitUtcAndAlignedInstantsPass(){assertDoesNotThrow(()->FinancialClockStartupGuard.verify("+00:00",NOW,EPOCH,NOW));}
    @Test void urlTimeZoneCannotSubstituteForActualSession(){for(String zone:new String[]{"SYSTEM","+08:00"})assertThrows(IllegalStateException.class,()->FinancialClockStartupGuard.verify(zone,NOW,EPOCH,NOW));}
    @Test void utcSessionWithWrongJdbcInterpretationFails(){assertThrows(IllegalStateException.class,()->FinancialClockStartupGuard.verify("+00:00",NOW.plusSeconds(28800),EPOCH,NOW));}
    @Test void driftingApplicationClockFails(){assertThrows(IllegalStateException.class,()->FinancialClockStartupGuard.verify("+00:00",NOW,EPOCH,NOW.minusSeconds(60)));}
    @Test void fractionalServerSecondsRetainExactSecondComparison(){assertDoesNotThrow(()->FinancialClockStartupGuard.verify("UTC",NOW,new BigDecimal(EPOCH+".123456"),NOW));}
}
