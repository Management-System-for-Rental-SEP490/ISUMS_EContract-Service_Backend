package com.isums.contractservice.configurations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class ReqLogFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        System.out.println("IN: " + req.getMethod() + " " + req.getRequestURI()
                + " remote=" + req.getRemoteAddr()
                + " xff=" + req.getHeader("X-Forwarded-For"));
        chain.doFilter(req, res);
    }
}

