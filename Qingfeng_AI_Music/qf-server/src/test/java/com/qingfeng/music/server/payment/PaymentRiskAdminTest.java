package com.qingfeng.music.server.payment;

import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Validation;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentRiskAdminTest {
    @Test void financialRoleIsIndependentAndDefaultDeny(){
        var risks=mock(PaymentRiskService.class);var controller=new PaymentRiskAdminController(risks,"finance-user");
        for(var principal:new CreatorPrincipal[]{null,new CreatorPrincipal("regular-user"),new CreatorPrincipal("analytics-admin")})
            assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.cases(principal)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->new PaymentRiskAdminController(risks,"").cases(new CreatorPrincipal("finance-user"))).getStatusCode().value());
        verifyNoInteractions(risks);
        controller.cases(new CreatorPrincipal("finance-user"));verify(risks).listOpen(100);
    }
    @Test void reviewerComesFromSessionAndStaleReviewReturnsConflict(){
        var risks=mock(PaymentRiskService.class);var controller=new PaymentRiskAdminController(risks,"finance-user");
        String id=UUID.randomUUID().toString(),reason="Verified provider dispute resolution evidence";
        when(risks.resolveCase(id,"event-1","RELEASE","finance-user",reason)).thenThrow(new IllegalStateException());
        var request=new PaymentRiskAdminController.Review("event-1","RELEASE",reason);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->controller.review(new CreatorPrincipal("finance-user"),id,request)).getStatusCode().value());
        verify(risks).resolveCase(id,"event-1","RELEASE","finance-user",reason);
    }
    @Test void nullDecisionEmptyEventAndShortReasonAreRejectedAtBoundary(){
        try(var factory=Validation.buildDefaultValidatorFactory()){
            assertEquals(3,factory.getValidator().validate(new PaymentRiskAdminController.Review("",null,"short")).size());
            assertTrue(factory.getValidator().validate(new PaymentRiskAdminController.Review("event-1","RELEASE","Verified the provider resolution")).isEmpty());
        }
    }
}
