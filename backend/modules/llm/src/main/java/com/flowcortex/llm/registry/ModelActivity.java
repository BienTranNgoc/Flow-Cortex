package com.flowcortex.llm.registry;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Whether registry entries exist for given model ids, in one query. */
@Component
public class ModelActivity {

    private final LlmModelRepository models;

    ModelActivity(LlmModelRepository models) {
        this.models = models;
    }

    /** The {@code active} flag of each id that has an entry; ids with no entry are absent from the map. */
    public Map<String, Boolean> activeByModelKey(Collection<String> modelKeys) {
        Map<String, Boolean> active = new HashMap<>();
        if (modelKeys.isEmpty()) {
            return active;
        }
        for (LlmModel model : models.findAllById(modelKeys)) {
            active.put(model.modelKey(), model.active());
        }
        return active;
    }
}
