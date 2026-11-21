package com.patterncatalyst.agentapi.afdeterministicdecisioning;

/**
 * Spike-only fact: de-risks runtime DRL compilation and rule firing on JDK 25 before the real
 * fact model is written. Not part of the shipped example.
 */
public class SpikeFact {

    private boolean fired = false;

    public boolean isFired() {
        return fired;
    }

    public void setFired(boolean fired) {
        this.fired = fired;
    }
}
