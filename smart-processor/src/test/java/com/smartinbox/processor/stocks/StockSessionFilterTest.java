package com.smartinbox.processor.stocks;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class StockSessionFilterTest {
    private MockHttpServletRequest request(String method,String path) {
        var r=new MockHttpServletRequest(method,path);r.setRemoteAddr("127.0.0.1");r.setServerName("127.0.0.1");return r;
    }
    @Test void localPageCanObtainCapabilityButCannotMutateWithoutIt() throws Exception {
        var f=new StockSessionFilter(); var response=new MockHttpServletResponse(); var chain=new MockFilterChain();
        f.doFilter(request("GET","/api/stocks/session"),response,chain); assertNotNull(chain.getRequest());
        response=new MockHttpServletResponse();chain=new MockFilterChain();
        f.doFilter(request("POST","/api/stocks/reports"),response,chain); assertEquals(403,response.getStatus());assertNull(chain.getRequest());
        var r=request("POST","/api/stocks/reports");r.addHeader("X-Stock-Session",f.issue());r.addHeader("Origin","http://127.0.0.1:5173");
        response=new MockHttpServletResponse();chain=new MockFilterChain();f.doFilter(r,response,chain);assertNotNull(chain.getRequest());assertEquals("no-store",response.getHeader("Cache-Control"));
    }
    @Test void remoteAndCrossSiteClientsCannotReadSecretsOrGetSession() throws Exception {
        for(String kind:new String[]{"remote","origin","fetch","forwarded","host"}) {
            var r=request("GET","/api/stocks/session");
            switch(kind) {case "remote"->r.setRemoteAddr("192.168.0.4");case "origin"->r.addHeader("Origin","https://evil.example");case "fetch"->r.addHeader("Sec-Fetch-Site","cross-site");case "forwarded"->r.addHeader("X-Forwarded-For","192.168.0.4, 127.0.0.1");case "host"->r.setServerName("evil.example");}
            var s=new MockHttpServletResponse();var chain=new MockFilterChain();new StockSessionFilter().doFilter(r,s,chain);assertEquals(403,s.getStatus(),kind);assertNull(chain.getRequest());
        }
    }
    @Test void onlyExplicitOAuthCallbackPathsBypassCapability() throws Exception {
        var r=request("GET","/api/stocks/auth/ibkr/callback");r.addHeader("Sec-Fetch-Site","cross-site");
        var chain=new MockFilterChain();new StockSessionFilter().doFilter(r,new MockHttpServletResponse(),chain);assertNotNull(chain.getRequest());
        var s=new MockHttpServletResponse();new StockSessionFilter().doFilter(request("GET","/api/stocks/auth/unknown/callback"),s,new MockFilterChain());assertEquals(403,s.getStatus());
    }
    @Test void encodedAndMatrixPathsCannotBypassNamespaceGuard() throws Exception {
        for(String path:new String[]{"/api/stocks;v=1/reports","/api/%73tocks/reports","/api/%2573tocks/reports","/api//stocks/reports","/api/temp/../stocks/reports"}) {
            var r=request("POST",path);var s=new MockHttpServletResponse();var chain=new MockFilterChain();
            new StockSessionFilter().doFilter(r,s,chain);assertEquals(403,s.getStatus(),path);assertNull(chain.getRequest());
        }
    }
}
