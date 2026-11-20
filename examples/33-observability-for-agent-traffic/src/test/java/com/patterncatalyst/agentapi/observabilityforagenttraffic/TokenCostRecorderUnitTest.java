package com.patterncatalyst.agentapi.observabilityforagenttraffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * A plain, no-CDI unit test of {@link TokenCostRecorder}: no Dev Services, no Quarkus test
 * lifecycle, just the class itself. Proves {@code cost.recorder.enabled=false} makes {@link
 * TokenCostRecorder#recordCost} inert before it ever touches a {@code DataSource}, which is why
 * the constructor here is handed {@code null} for it: a recorder that tried to open a connection
 * while disabled would throw a {@link NullPointerException} and fail this test immediately,
 * instead of quietly skipping the row the way the negative witness in {@code verify.sh} relies on.
 */
class TokenCostRecorderUnitTest {

    private static final BigDecimal INPUT_RATE_PER_1K = new BigDecimal("0.0005");
    private static final BigDecimal OUTPUT_RATE_PER_1K = new BigDecimal("0.0015");

    @Test
    void aDisabledRecorderNeverTouchesTheDataSourceAndRecordsNothing() {
        var recorder = new TokenCostRecorder(null, new SimpleMeterRegistry(), false, INPUT_RATE_PER_1K,
                OUTPUT_RATE_PER_1K);

        boolean recorded = recorder.recordCost("call-disabled-1", "qwen3-fixture", 128, 42);

        assertFalse(recorded, "a disabled recorder must report that it recorded nothing");
    }

    @Test
    void costGrowsLinearlyWithEachTokenCountAtTheFixedRates() {
        var recorder = new TokenCostRecorder(null, new SimpleMeterRegistry(), false, INPUT_RATE_PER_1K,
                OUTPUT_RATE_PER_1K);

        // 1000 input tokens at $0.0005/1k plus 1000 output tokens at $0.0015/1k is $0.002000.
        assertEquals(new BigDecimal("0.002000"), recorder.cost(1000, 1000));
    }
}
