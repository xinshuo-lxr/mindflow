package com.xinshuo.mindflow.user.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SaToken 配置类
 * 配置登录拦截和用户上下文拦截器
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfig implements WebMvcConfigurer {

    private final UserContextInterceptor userContextInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 SaToken 登录拦截器
        registry.addInterceptor(new SaInterceptor(handler -> {
                    // 异步调度请求跳过登录检查
                    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                    if (attrs != null) {
                        HttpServletRequest request = attrs.getRequest();
                        if (request.getDispatcherType() == DispatcherType.ASYNC) {
                            return;
                        }
                        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                            return;
                        }
                    }
                    StpUtil.checkLogin();
                }))
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/**", "/error");

        // 注册用户上下文拦截器
        registry.addInterceptor(userContextInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/**", "/error");
    }
}
