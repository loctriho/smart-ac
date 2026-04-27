package com.smartac.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the admin single-page app (jQuery + vanilla JS) from {@code classpath:/static/admin/} and
 * deep-links to {@code /admin/index.html} for unknown paths under {@code /admin/}.
 */
@Configuration
public class AdminSpaWebConfig implements WebMvcConfigurer {

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addRedirectViewController("/admin", "/admin/");
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/admin/**")
        .addResourceLocations("classpath:/static/admin/")
        .resourceChain(true)
        .addResolver(
            new PathResourceResolver() {
              @Override
              protected Resource getResource(String resourcePath, Resource location) throws IOException {
                if (resourcePath == null || resourcePath.isEmpty() || ".".equals(resourcePath)) {
                  return super.getResource("index.html", location);
                }
                Resource resource = super.getResource(resourcePath, location);
                if (resource != null && resource.exists()) {
                  return resource;
                }
                return super.getResource("index.html", location);
              }
            });
  }
}
