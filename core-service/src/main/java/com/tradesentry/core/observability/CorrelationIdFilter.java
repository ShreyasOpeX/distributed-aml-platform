package com.tradesentry.core.observability;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER="X-Correlation-Id";
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
                                              FilterChain chain)throws ServletException,IOException{
        String id=request.getHeader(HEADER);
        if(id==null||id.isBlank()) id=UUID.randomUUID().toString();
        MDC.put("correlationId",id); response.setHeader(HEADER,id);
        try{chain.doFilter(request,response);}finally{MDC.remove("correlationId");}
    }
}
