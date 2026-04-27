package com.smartac.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.smartac.device.service.DeviceReadingsExecutor;

@Configuration
public class DeviceIngestExecutorConfig {

  @Bean(name = "deviceReadingsExecutor", destroyMethod = "destroy")
  public DeviceReadingsExecutor deviceReadingsExecutor(DeviceIngestProperties props) {
    int maxInFlight = Math.max(1, props.getWorkerThreads());
    int queueCapacity = Math.max(1, props.getQueueCapacity());
    return new BoundedVirtualThreadExecutor(maxInFlight, queueCapacity, "device-readings-");
  }
}
