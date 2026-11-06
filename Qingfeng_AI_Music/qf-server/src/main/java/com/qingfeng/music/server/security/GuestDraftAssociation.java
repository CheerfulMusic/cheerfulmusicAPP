package com.qingfeng.music.server.security;

import jakarta.servlet.http.HttpServletRequest;

/** Server-confirmed guest association survives the login redirect; never trust creator IDs in URL parameters. */
public final class GuestDraftAssociation {
    private static final String KEY=GuestDraftAssociation.class.getName();
    private record Association(String source,String target,long created) {}
    private GuestDraftAssociation() {}
    public static void prepare(HttpServletRequest request,String source,String target) {
        if(source!=null&&target!=null&&!source.equals(target))request.setAttribute(KEY,new Association(source,target,System.currentTimeMillis()));
    }
    public static void rotate(HttpServletRequest request) {
        var old=request.getSession(false);if(old!=null)old.invalidate();
        if(request.getAttribute(KEY) instanceof Association association) {
            var next=request.getSession(true);next.setMaxInactiveInterval(600);next.setAttribute(KEY,association);
        }
    }
    public static String source(HttpServletRequest request,String currentCreator) {
        var session=request.getSession(false);
        if(session!=null&&session.getAttribute(KEY) instanceof Association association
            &&association.target().equals(currentCreator)&&System.currentTimeMillis()-association.created()<600000)return association.source();
        return null;
    }
}
