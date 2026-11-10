package com.flowcortex.billing.region;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentsRegionProperties.class)
class PaymentsRegionConfig {

    @Bean
    PaymentsRegionInterceptor paymentsRegionInterceptor(PaymentsRegionService service,
            PaymentsRegionProperties properties, RegionTelemetry telemetry) {
        return new PaymentsRegionInterceptor(service, properties, telemetry);
    }

    @Bean
    WebMvcConfigurer paymentsRegionMvc(PaymentsRegionInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/api/**");
            }
        };
    }
}
