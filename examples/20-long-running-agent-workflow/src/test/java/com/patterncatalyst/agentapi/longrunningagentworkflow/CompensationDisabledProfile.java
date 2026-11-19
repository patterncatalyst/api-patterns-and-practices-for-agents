package com.patterncatalyst.agentapi.longrunningagentworkflow;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/** Demo code switched off: COMPENSATE=off. */
public class CompensationDisabledProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("fulfillment.compensation.enabled", "false");
    }
}
