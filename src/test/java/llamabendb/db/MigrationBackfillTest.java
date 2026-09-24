package llamabendb.db;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the H2 migrations up to V3, seeds V3-state result rows, applies V4 and
 * checks the compute backfill plus the computer_version.devices default.
 */
class MigrationBackfillTest {

    @Test
    void v4BackfillsComputeFromRawColumns() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:backfill;DB_CLOSE_DELAY=-1", "sa", "")) {
            for (String f : List.of("V1__init.sql", "V2__result_build.sql", "V3__computer_hostname.sql")) {
                execute(c, readSql(f));
            }
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO computer (name) VALUES ('bf-box')");
                st.execute("INSERT INTO computer_version (computer_id, created_at) VALUES (1, NOW())");
                st.execute("INSERT INTO model (name, model_id, quantization, quant_sort_key)"
                        + " VALUES ('m', 'acme/M-GGUF', 'Q4_K_M', 'q4_k_m')");

                insertResult(c, "Vulkan0", "Vulkan");           // explicit dev column: copied verbatim
                insertResult(c, "none", "Vulkan");              // dev none: CPU run
                insertResult(c, "  NONE ", "Vulkan");           // trimmed + case-insensitive
                insertResult(c, null, "Vulkan");                // no dev column, only Vulkan: assume Vulkan0
                insertResult(c, null, "CPU");                   // no dev column, CPU backend
                insertResult(c, null, "Vulkan,OPENVINO");       // inconclusive
                insertResult(c, null, null);                    // nothing to go on
                insertResult(c, "CUDA0", "CUDA,Vulkan");        // explicit dev column wins over backend
            }

            execute(c, readSql("V4__compute_devices.sql"));

            List<String> computes = new ArrayList<>();
            try (ResultSet rs = c.createStatement().executeQuery("SELECT compute FROM result ORDER BY id")) {
                while (rs.next()) {
                    computes.add(rs.getString(1));
                }
            }
            assertEquals(List.of(
                    "Vulkan0", "CPU", "CPU", "Vulkan0", "CPU", "unknown", "unknown", "CUDA0"), computes);

            // pre-V4 rows have no hardware data yet
            try (ResultSet rs = c.createStatement().executeQuery("SELECT devices FROM computer_version WHERE id = 1")) {
                assertTrue(rs.next());
                assertNull(rs.getString(1));
            }
        }
    }

    @Test
    void v5ConsolidatesComputeIntoDevices() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:v5;DB_CLOSE_DELAY=-1", "sa", "")) {
            for (String f : List.of("V1__init.sql", "V2__result_build.sql", "V3__computer_hostname.sql")) {
                execute(c, readSql(f));
            }
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO computer (name) VALUES ('v5-box')");
                st.execute("INSERT INTO computer_version (computer_id, created_at) VALUES (1, NOW())");
                st.execute("INSERT INTO model (name, model_id, quantization, quant_sort_key)"
                        + " VALUES ('m', 'acme/M-GGUF', 'Q4_K_M', 'q4_k_m')");

                insertResult(c, "Vulkan0", "Vulkan");           // explicit dev column: copied verbatim by V4
                insertResult(c, "none", "Vulkan");              // dev none: CPU run
                insertResult(c, "  NONE ", "Vulkan");           // trimmed + case-insensitive
                insertResult(c, null, "Vulkan");                // no dev column, only Vulkan: assume Vulkan0
                insertResult(c, null, "CPU");                   // no dev column, CPU backend
                insertResult(c, null, "Vulkan,OPENVINO");       // inconclusive
                insertResult(c, null, null);                    // nothing to go on
                insertResult(c, "CUDA0", "CUDA,Vulkan");        // explicit dev column wins over backend
                // params written as a SQL string literal: H2 stores that as a JSON
                // string, which V5 must normalize while consolidating.
                st.execute("INSERT INTO result (computer_version_id, model_id, imported_at, devices, backend,"
                        + " pp_tokens, tg_tokens, params) VALUES (1, 1, NOW(), 'CUDA0', 'CUDA', 512, 128, '{}')");
            }

            execute(c, readSql("V4__compute_devices.sql"));

            // Simulate a row imported after V4 whose resolved value differs from
            // the raw dev column (e.g. MoE offload adds CPU): id 1 gets it.
            try (Statement st = c.createStatement()) {
                st.execute("UPDATE result SET compute = 'Vulkan0,CPU' WHERE id = 1");
            }

            new V5__DevicesConsolidation().migrate(new Context() {
                @Override
                public org.flywaydb.core.api.configuration.Configuration getConfiguration() {
                    return null;
                }

                @Override
                public Connection getConnection() {
                    return c;
                }
            });

            List<String> devices = new ArrayList<>();
            List<String> params = new ArrayList<>();
            try (ResultSet rs = c.createStatement().executeQuery("SELECT devices, params FROM result ORDER BY id")) {
                while (rs.next()) {
                    devices.add(rs.getString(1));
                    params.add(rs.getString(2));
                }
            }
            assertEquals(Arrays.asList("Vulkan0,CPU", "CPU", "CPU", "Vulkan0", "CPU", null, null, "CUDA0", "CUDA0"), devices);

            ObjectMapper json = new ObjectMapper();
            Map<String, Object> p1 = json.readValue(params.get(0), LinkedHashMap.class);
            assertEquals("Vulkan0", p1.get("dev")); // raw value kept as a record
            assertEquals("none", json.readValue(params.get(1), LinkedHashMap.class).get("dev"));
            assertEquals("NONE", json.readValue(params.get(2), LinkedHashMap.class).get("dev"));
            assertEquals(Map.of(), json.readValue(params.get(3), LinkedHashMap.class)); // no dev column: untouched
            assertEquals(Map.of(), json.readValue(params.get(5), LinkedHashMap.class)); // unknown: untouched
            // the literal-inserted row was normalized from a JSON string to an object
            assertEquals("CUDA0", json.readValue(params.get(8), LinkedHashMap.class).get("dev"));

            DatabaseMetaData meta = c.getMetaData();
            boolean hasCompute = false;
            try (ResultSet rs = meta.getColumns(null, null, "RESULT", "COMPUTE")) {
                hasCompute = rs.next();
            }
            assertFalse(hasCompute, "compute column should be dropped");
        }
    }

    // params is written via setBytes, the way Hibernate's H2JsonJdbcType does:
    // H2 stores VARCHAR values in JSON columns as JSON *strings*, which would
    // not match production data.
    private static void insertResult(Connection c, String devices, String backend) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO result"
                        + " (computer_version_id, model_id, imported_at, devices, backend,"
                        + " pp_tokens, tg_tokens, params) VALUES (1, 1, NOW(), ?, ?, 512, 128, ?)")) {
            ps.setString(1, devices);
            ps.setString(2, backend);
            ps.setBytes(3, "{}".getBytes(StandardCharsets.UTF_8));
            ps.executeUpdate();
        }
    }

    private static void execute(Connection c, String script) throws Exception {
        // strip comment lines (they may contain semicolons) before splitting
        String stripped = Arrays.stream(script.split("\n"))
                .filter(l -> !l.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));
        for (String statement : stripped.split(";")) {
            if (!statement.isBlank()) {
                try (Statement st = c.createStatement()) {
                    st.execute(statement);
                }
            }
        }
    }

    private static String readSql(String file) throws IOException {
        try (InputStream in = MigrationBackfillTest.class.getResourceAsStream("/db/migration/h2/" + file)) {
            if (in == null) {
                throw new IllegalStateException("missing migration /db/migration/h2/" + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
