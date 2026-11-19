package com.patterncatalyst.agentapi.replayableagentmemoryeventsourcedaudit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.patterncatalyst.agentapi.Carrier;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The outbound side's decision source: a committed, recorded model call, replayed from
 * {@code fixtures/model-decisions.jsonl}, never a live model. This chapter's checks are a pure
 * log/fold over the append-only audit log: whether a replay reproduces the live projection does
 * not depend on what the model decided, only on the log being read the same way twice, so this
 * chapter has no agent nondeterminism to model in the first place and no live Ollama path to
 * toggle off for tests (contrast chapter 17's {@code agent.wakeup.model-source}, which does need
 * one). An order id the fixture does not name falls back to a fixed rule (FASTLINE, the
 * cheaper, slower carrier) rather than guessing, so the fallback is as deterministic as the
 * fixture itself.
 *
 * <p>{@code costUsd} is derived from the token counts alone (never from content), the same
 * discipline the OTel GenAI semantic conventions call for while content capture stays off (see
 * {@code _plans/versions.md}): a fixed, published per-token rate for a small local model,
 * input and output priced separately because a real model usually prices them differently.
 */
@ApplicationScoped
public class AuditModelDecisionAdvisor {

    /** What a (recorded) model call decided, and what it cost. */
    public record Decision(Carrier carrier, int tokensIn, int tokensOut, BigDecimal costUsd, String modelId) {
    }

    private static final String FIXTURE_PATH = "fixtures/model-decisions.jsonl";
    private static final String MODEL_ID = "qwen3-fixture:recorded-2026-11-19";
    // Fictional per-token rates for a small local model: USD per input / output token.
    private static final BigDecimal RATE_IN = new BigDecimal("0.0000003");
    private static final BigDecimal RATE_OUT = new BigDecimal("0.0000006");
    private static final Carrier FALLBACK_CARRIER = Carrier.FASTLINE;
    private static final int FALLBACK_TOKENS_IN = 30;
    private static final int FALLBACK_TOKENS_OUT = 10;

    private final Map<String, Decision> recorded = load();

    public Decision decide(String orderId) {
        Decision fixture = recorded.get(orderId);
        if (fixture != null) {
            return fixture;
        }
        return cost(FALLBACK_CARRIER, FALLBACK_TOKENS_IN, FALLBACK_TOKENS_OUT);
    }

    private static Decision cost(Carrier carrier, int tokensIn, int tokensOut) {
        BigDecimal costUsd = RATE_IN.multiply(BigDecimal.valueOf(tokensIn))
                .add(RATE_OUT.multiply(BigDecimal.valueOf(tokensOut)))
                .setScale(6, RoundingMode.HALF_UP);
        return new Decision(carrier, tokensIn, tokensOut, costUsd, MODEL_ID);
    }

    private static Map<String, Decision> load() {
        var decisions = new HashMap<String, Decision>();
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(FIXTURE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found on the classpath: " + FIXTURE_PATH);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = mapper.readTree(line);
                    Carrier carrier = Carrier.valueOf(node.get("carrier").asText());
                    int tokensIn = node.get("tokensIn").asInt();
                    int tokensOut = node.get("tokensOut").asInt();
                    decisions.put(node.get("orderId").asText(), cost(carrier, tokensIn, tokensOut));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + FIXTURE_PATH, e);
        }
        return decisions;
    }
}
