package cn.timebank.common.web;

import cn.timebank.common.pagination.PageQueryArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PageQueryArgumentResolver pageQueryArgumentResolver;

    public WebMvcConfig(PageQueryArgumentResolver pageQueryArgumentResolver) {
        this.pageQueryArgumentResolver = pageQueryArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(pageQueryArgumentResolver);
    }
}
