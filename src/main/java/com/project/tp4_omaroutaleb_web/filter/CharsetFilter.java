package com.project.tp4_omaroutaleb_web.filter;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;

import java.io.IOException;

@WebFilter("/*")
public class CharsetFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
//        HttpServletResponse httpServletResponse = (HttpServletResponse) response;
//        httpServletResponse.setContentType("text/html; charset=UTF-8");
//        httpServletResponse.setCharacterEncoding("UTF-8");

        request.setCharacterEncoding("UTF-8");

        chain.doFilter(request, response);
    }
}
