package cn.crabc.core.app.config;

import cn.crabc.core.app.filter.ApiFilter;
import cn.crabc.core.app.filter.AuthInterceptor;
import cn.crabc.core.app.filter.JwtInterceptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;



/**
 * 注册拦截器
 *
 * @author yuqf
 */
@Configuration
public class InterceptorConfig implements WebMvcConfigurer {

    @Bean
    public AuthInterceptor apiInterceptor() {
        return new AuthInterceptor();
    }

    @Bean
    public JwtInterceptor jwtInterceptor() {
        return new JwtInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器（chatView：/refresh 凭 refresh token 换发，不需要已有登录态，放行）
        registry.addInterceptor(jwtInterceptor())
                .addPathPatterns("/api/box/**") // 需要拦截的请求
                .excludePathPatterns("/api/box/sys/user/login", "/api/box/sys/user/loginout",
                        "/api/box/sys/user/register", "/api/box/sys/user/refresh"); // 不拦截的请求

        // chatView：创作会话接口同样走 crabc JWT 登录态
        registry.addInterceptor(jwtInterceptor())
                .addPathPatterns("/api/v1/**");

        // API开放接口拦截器
        registry.addInterceptor(apiInterceptor())
                .addPathPatterns("/api/web/**"); // 需要拦截的请求
    }

    /**
     * 日志过滤器
     * @return
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Bean
    public FilterRegistrationBean builderRegistrationBean(){
        FilterRegistrationBean registration = new FilterRegistrationBean();
        registration.setFilter(new ApiFilter());
        registration.addUrlPatterns("/api/web/*");
        registration.setName("apiFilter");
        registration.setOrder(-1);
        return registration;
    }
}
