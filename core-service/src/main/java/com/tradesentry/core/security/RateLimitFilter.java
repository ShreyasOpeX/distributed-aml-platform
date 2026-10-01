package com.tradesentry.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private record Window(long epochSecond, AtomicInteger count) {}
    private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();
    private final int requestsPerSecond;

    public RateLimitFilter(@Value("${tradesentry.rate-limit.requests-per-second:50}") int requestsPerSecond){
        this.requestsPerSecond=requestsPerSecond;
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
                                              FilterChain chain)throws ServletException,IOException{
        if(!request.getRequestURI().startsWith("/api/transactions")){chain.doFilter(request,response);return;}
        String key=request.getRemoteAddr();
        long second=Instant.now().getEpochSecond();
        Window window=windows.compute(key,(k,w)->w==null||w.epochSecond()!=second?new Window(second,new AtomicInteger(0)):w);
        if(window.count().incrementAndGet()>requestsPerSecond){
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After","1");
            return;
        }
        chain.doFilter(request,response);
    }
}
