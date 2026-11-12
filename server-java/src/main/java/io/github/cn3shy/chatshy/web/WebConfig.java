package io.github.cn3shy.chatshy.web;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AdminAuthInterceptor adminAuthInterceptor;

    public WebConfig(AdminAuthInterceptor adminAuthInterceptor) {
        this.adminAuthInterceptor = adminAuthInterceptor;
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("*");
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Authorization", "x-api-key", "anthropic-version", "x-chatshy-caller"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 只对网关 API 与操作台 API 开放跨域；豆奶的 /api/chat 是公开代理，
        // 保持同源限制，避免被任意网站当作免费上游使用。
        source.registerCorsConfiguration("/v1/**", config);
        source.registerCorsConfiguration("/admin/api/**", config);
        return new CorsFilter(source);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/api/**")
                .excludePathPatterns("/admin/api/login");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(fileLocation("web/dist/assets"))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/dounai-assets/**")
                .addResourceLocations(fileLocation("dounai/dist/dounai-assets"))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/icon.jpg")
                .addResourceLocations(fileLocation("dounai/dist"))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
    }

    private static String fileLocation(String relative) {
        String uri = Path.of(relative).toAbsolutePath().normalize().toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }
}
