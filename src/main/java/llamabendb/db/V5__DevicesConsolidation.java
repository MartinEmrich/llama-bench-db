package llamabendb.db;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One-time consolidation of the compute column into devices (V5).
 * <p>
 * devices becomes the single record of which devices a run used: it takes the
 * resolved value that compute held, or null where compute was 'unknown'. The
 * raw llama-bench dev column value is preserved in the params JSON under "dev"
 * as a record of what the run printed. Runs as a Java migration because H2 has
 * no JSON merge function (PostgreSQL/MariaDB would need different SQL anyway);
 * Spring Boot hands every JavaMigration bean to Flyway.
 * <p>
 * H2 quirk: VARCHAR values assigned to a JSON column are stored as JSON
 * *strings*, so params is written via setBytes, exactly like Hibernate's
 * H2JsonJdbcType does (which is why production rows hold real JSON objects).
 */
@Component
public class V5__DevicesConsolidation implements JavaMigration {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("5");
    }

    @Override
    public String getDescription() {
        return "consolidate compute into devices";
    }

    @Override
    public Integer getChecksum() {
        return null;
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        String product = conn.getMetaData().getDatabaseProductName().toLowerCase();
        boolean postgres = product.contains("postgres");
        boolean h2 = product.contains("h2");
        String updateSql = postgres
                ? "UPDATE result SET devices = ?, params = CAST(? AS jsonb) WHERE id = ?"
                : "UPDATE result SET devices = ?, params = ? WHERE id = ?";

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, devices, compute, params FROM result")) {
            while (rs.next()) {
                long id = rs.getLong(1);
                String rawDevices = trimToNull(rs.getString(2));
                String compute = trimToNull(rs.getString(3));
                Map<String, Object> params = parseParams(rs.getString(4));

                boolean changed = false;
                if (rawDevices != null) {
                    params.put("dev", rawDevices);
                    changed = true;
                }
                String devices = (compute == null || compute.equalsIgnoreCase("unknown")) ? null : compute;
                if (!Objects.equals(devices, rawDevices)) {
                    changed = true;
                }
                if (!changed) {
                    continue;
                }
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, devices);
                    String json = JSON.writeValueAsString(params);
                    if (h2) {
                        ps.setBytes(2, json.getBytes(StandardCharsets.UTF_8));
                    } else {
                        ps.setString(2, json);
                    }
                    ps.setLong(3, id);
                    ps.executeUpdate();
                }
            }
        }

        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE result DROP COLUMN compute");
        }
    }

    private static Map<String, Object> parseParams(String text) throws Exception {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        JsonNode node = JSON.readTree(text);
        if (node.isTextual()) {
            // H2 wraps SQL string literals in JSON columns as JSON strings;
            // unwrap so hand-inserted rows migrate the same as Hibernate-written ones.
            node = JSON.readTree(node.asText());
        }
        return JSON.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}
