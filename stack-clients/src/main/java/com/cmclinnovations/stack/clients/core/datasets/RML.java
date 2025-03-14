package com.cmclinnovations.stack.clients.core.datasets;

import java.nio.file.Path;
import java.util.Map;

import com.cmclinnovations.stack.clients.rml.RmlMapperClient;

public class RML extends DataSubset {

  @Override
  public boolean usesBlazegraph() {
    return !isSkip();
  }

  @Override
  void loadInternal(Dataset parent) {
    Path subdirectory = this.getSubdirectory()
        .orElseThrow(() -> new RuntimeException("No 'subdirectory' specified - required for RML data"));
    Map<String, byte[]> rmlRules = RmlMapperClient.getInstance().parseYarrrmlToRml(
        parent.getDirectory().resolve(subdirectory), parent.getNamespace());
    RmlMapperClient.getInstance().parseRmlToRDF(rmlRules);
  }
}
