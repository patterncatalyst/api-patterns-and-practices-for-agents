package com.patterncatalyst.agentapi.agentbehavioureval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads one of the two committed, recorded transcripts under {@code
 * src/main/resources/fixtures/} from the classpath. {@link EvalRoute} only ever passes the two
 * literal names {@code "good-run.jsonl"} and {@code "regressed-run.jsonl"}, both fixed in code,
 * so there is no caller-controlled path to validate here.
 */
final class FixtureTranscripts {

    private FixtureTranscripts() {
    }

    static Transcript read(String fixtureName) {
        String resource = "fixtures/" + fixtureName;
        try (InputStream in = FixtureTranscripts.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found on the classpath: " + resource);
            }
            return TranscriptReader.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + resource, e);
        }
    }
}
