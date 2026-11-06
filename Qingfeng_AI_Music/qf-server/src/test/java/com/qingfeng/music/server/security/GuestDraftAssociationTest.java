package com.qingfeng.music.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class GuestDraftAssociationTest {
    @Test void confirmedAssociationSurvivesRotationAndIsBoundToTargetAccount() {
        var request=new MockHttpServletRequest();var old=request.getSession(true);
        GuestDraftAssociation.prepare(request,"guest","account");GuestDraftAssociation.rotate(request);
        assertNotSame(old,request.getSession(false));
        assertEquals("guest",GuestDraftAssociation.source(request,"account"));
        assertNull(GuestDraftAssociation.source(request,"other-account"));
        assertEquals("guest",GuestDraftAssociation.source(request,"account"));
    }
    @Test void requestParametersAndUnconfirmedLoginCannotGrantDraftAccess() {
        var request=new MockHttpServletRequest();request.setParameter("associatedGuestCreatorId","other");
        assertNull(GuestDraftAssociation.source(request,"account"));
        GuestDraftAssociation.rotate(request);assertNull(request.getSession(false));
        GuestDraftAssociation.prepare(request,"same","same");GuestDraftAssociation.rotate(request);
        assertNull(GuestDraftAssociation.source(request,"same"));
    }
}
