package com.qingfeng.music.server.payment;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentInboxRunnerTest {
    @Test void pollingIsBoundedAndRotatesAcrossIndependentProviders(){
        var calls=new ArrayList<String>();
        var first=processor("FIRST",calls,true);var second=processor("SECOND",calls,true);
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(first,second));
        try{
            runner.pollBatch();assertEquals(10,calls.size());
            for(int n=0;n<10;n++)assertEquals(n%2==0?"FIRST":"SECOND",calls.get(n));
        }finally{runner.stop();}
    }
    @Test void anEmptyPassStopsWithoutSpinningAndDuplicateChannelsAreRejected(){
        var calls=new ArrayList<String>();var first=processor("FIRST",calls,false);var second=processor("SECOND",calls,false);
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(first,second));
        try{runner.pollBatch();assertEquals(List.of("FIRST","SECOND"),calls);}finally{runner.stop();}
        assertThrows(IllegalArgumentException.class,()->new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(first,first)));
    }
    private VerifiedPaymentProcessor processor(String provider,List<String> calls,boolean pending){
        return new VerifiedPaymentProcessor(){public String provider(){return provider;}public boolean processNextEvent(){calls.add(provider);return pending;}};
    }
}
