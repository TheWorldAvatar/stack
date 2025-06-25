package com.cmclinnovations.stack;

import com.cmclinnovations.stack.clients.core.StackClient;
import com.cmclinnovations.stack.clients.core.datasets.DatasetLoader;

/**
 * Hello world!
 *
 */
public class DataUploader {
    public static void main(String[] args) {
        // The data uploader should *never* try to pull images!
        StackClient.setIsolated(true);

        new DatasetLoader().loadInputDatasets(StackClient.STACK_CONFIG_DIR, StackClient.getStackName());

    }
}
