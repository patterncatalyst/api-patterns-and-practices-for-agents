package com.patterncatalyst.agentapi.observabilityforagenttraffic;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Writes the durable, per-call record this chapter's pattern is about: one {@code token_cost}
 * row per model call, derived from the call's own token counts, independent of whatever the
 * model's GenAI span and {@code gen_ai.client.token.usage} metric also report on their own.
 *
 * <p>Registered as a plain CDI bean implementing {@link ChatModelListener}, quarkus-langchain4j
 * picks it up the same way it wires in its own span and metrics listeners: a typesafe
 * {@code Instance<ChatModelListener>} lookup at chat-model build time, so no extra configuration
 * names this class. {@link #onRequest} stashes a call id in the shared attributes map every
 * {@code ChatModelListener} callback for one call receives; {@link #onResponse} reads the real
 * token counts straight off the model's own response and persists them. The MODEL=off fixture
 * path ({@link FixtureTokenUsageAdvisor}) never goes through a chat model call at all, so it
 * calls {@link #recordCost} directly with a recorded token count instead.
 *
 * <p>Cost is derived from a fixed, documented per-1000-token USD rate ({@code
 * cost.recorder.rate.input-per-1k}, {@code cost.recorder.rate.output-per-1k}), never from the
 * framework's own {@code gen_ai.client.estimated_cost} span attribute: that attribute lives only
 * on a span, while this chapter's claim is a durable row a cost report can query after the trace
 * has expired.
 *
 * <p>{@code cost.recorder.enabled=false} (demo code, {@code COST_RECORDER_ENABLED} in compose) is
 * the negative witness: {@link #recordCost} returns without ever touching the {@link DataSource},
 * so no row, no counter increment and no log line happen for that call, while the GenAI span and
 * the token-usage metric the framework emits on its own are untouched, since this flag governs
 * only this class.
 */
@ApplicationScoped
public class TokenCostRecorder implements ChatModelListener {

    private static final Logger LOG = Logger.getLogger(TokenCostRecorder.class);
    private static final String CALL_ID_ATTRIBUTE = "agenttraffic.callId";

    private final DataSource dataSource;
    private final Counter recordings;
    private final boolean enabled;
    private final BigDecimal inputRatePer1k;
    private final BigDecimal outputRatePer1k;

    @Inject
    public TokenCostRecorder(
            DataSource dataSource,
            MeterRegistry registry,
            @ConfigProperty(name = "cost.recorder.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "cost.recorder.rate.input-per-1k") BigDecimal inputRatePer1k,
            @ConfigProperty(name = "cost.recorder.rate.output-per-1k") BigDecimal outputRatePer1k) {
        this.dataSource = dataSource;
        this.enabled = enabled;
        this.inputRatePer1k = inputRatePer1k;
        this.outputRatePer1k = outputRatePer1k;
        this.recordings = Counter.builder("agenttraffic.cost.recordings")
                .description("Number of token_cost rows TokenCostRecorder has written")
                .register(registry);
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        requestContext.attributes().put(CALL_ID_ATTRIBUTE, UUID.randomUUID().toString());
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        Object callId = responseContext.attributes().get(CALL_ID_ATTRIBUTE);
        ChatResponse response = responseContext.chatResponse();
        TokenUsage usage = response.tokenUsage();
        if (usage == null) {
            LOG.warn("chat response carried no token usage; no token_cost row written");
            return;
        }
        String model = response.metadata() == null ? null : response.metadata().modelName();
        recordCost(callId == null ? UUID.randomUUID().toString() : callId.toString(), model,
                usage.inputTokenCount(), usage.outputTokenCount());
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        LOG.warnf(errorContext.error(), "chat model call failed; no token_cost row written");
    }

    /**
     * Derives a cost from a call's token counts at the fixed per-1000-token rates and persists
     * one {@code agenttraffic.token_cost} row, keyed by {@code callId}. Returns {@code false}
     * without touching the {@link DataSource} when {@code cost.recorder.enabled=false}, so a
     * disabled recorder is provably inert, not merely configured to no-op after a connection is
     * already open.
     */
    public boolean recordCost(String callId, String model, int inputTokens, int outputTokens) {
        if (!enabled) {
            return false;
        }
        BigDecimal cost = cost(inputTokens, outputTokens);
        try (Connection c = dataSource.getConnection();
                PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO agenttraffic.token_cost "
                                + "(call_id, model, input_tokens, output_tokens, cost_usd) "
                                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (call_id) DO NOTHING")) {
            insert.setString(1, callId);
            insert.setString(2, model);
            insert.setInt(3, inputTokens);
            insert.setInt(4, outputTokens);
            insert.setBigDecimal(5, cost);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("token_cost insert failed for call " + callId, e);
        }
        recordings.increment();
        LOG.infof("token cost recorded: callId=%s model=%s inputTokens=%d outputTokens=%d costUsd=%s",
                callId, model, inputTokens, outputTokens, cost);
        return true;
    }

    /** The one place the per-1000-token rate constants turn into a call's cost. */
    public BigDecimal cost(int inputTokens, int outputTokens) {
        BigDecimal inputCost = inputRatePer1k.multiply(BigDecimal.valueOf(inputTokens))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        BigDecimal outputCost = outputRatePer1k.multiply(BigDecimal.valueOf(outputTokens))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP);
    }
}
