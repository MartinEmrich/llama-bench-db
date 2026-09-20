package llamabendb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads sanitized llama-bench transcript fixtures from the test classpath. */
public final class TestResources {

    private TestResources() {
    }

    public static String readSample(String name) throws IOException {
        try (InputStream in = TestResources.class.getResourceAsStream("/samples/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource /samples/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
