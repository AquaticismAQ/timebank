package cn.timebank.common.web;

import cn.timebank.common.pagination.PageQueryArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PageQueryArgumentResolver pageQueryArgumentResolver;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(PageQueryArgumentResolver pageQueryArgumentResolver, RateLimitInterceptor rateLimitInterceptor) {
        this.pageQueryArgumentResolver = pageQueryArgumentResolver;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(pageQueryArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor);
    }
}
