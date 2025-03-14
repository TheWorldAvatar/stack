package com.cmclinnovations.stack.services;

import com.cmclinnovations.stack.services.config.ServiceConfig;

public class RmlMapperJavaService extends ContainerService {

  public static final String TYPE = "rml-mapper";

  public RmlMapperJavaService(String stackName, ServiceConfig config) {
    super(stackName, config);
  }
}
