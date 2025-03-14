package com.cmclinnovations.stack.clients.core.datasets;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;

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
    ByteArrayOutputStream generatedRmlRules = RmlMapperClient.getInstance().parseYarrrmlToRml(
        parent.getDirectory().resolve(subdirectory), parent.getNamespace());
    InputStream rmlRules = new ByteArrayInputStream(generatedRmlRules.toByteArray());
    RmlMapperClient.getInstance().parseRmlToRDF(rmlRules);
  }
}
