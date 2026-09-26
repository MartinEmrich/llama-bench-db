package llamabendb.api;

import llamabendb.TestResources;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private static final String MINIMAL_TABLE = """
            | model | size | backend | test | t/s |
            | ----- | ----: | ------- | ---: | --: |
            | m 4B Q4 | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |
            | m 4B Q4 | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |
            """;

    // Test methods share one in-memory DB, so every test uses its own computer
    // and model names to stay independent of execution order.

    private static String autoDetectPaste(String host, String hfArg) {
        return """
                martin@%1$s:~/upstream/llama.cpp$ build/bin/llama-bench %2$s -ctk q8_0
                Downloading Auto-4B-Q4_K_M.gguf ───────────────────────────────── 100%%
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | auto 4B Q4_K - Medium | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |
                | auto 4B Q4_K - Medium | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |
                """.formatted(host, hfArg);
    }

    private static String twoRunsPaste(String host, String repo4b, String repo2b) {
        return """
                martin@%1$s:~$ build/bin/llama-bench -hf %2$s
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | auto 4B Q4_K - Medium | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |
                | auto 4B Q4_K - Medium | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |

                martin@%1$s:~$ build/bin/llama-bench -hf %3$s
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | auto 2B Q4_K - Medium | 1.2 GiB | CPU | pp512 | 1.8 ± 0.0 |
                | auto 2B Q4_K - Medium | 1.2 GiB | CPU | tg128 | 1.3 ± 0.0 |
                """.formatted(host, repo4b, repo2b);
    }

    private JsonNode postJson(String path, Object body) throws Exception {
        String response = mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body instanceof String s ? s : json.writeValueAsString(body)))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private JsonNode getJson(String path) throws Exception {
        String response = mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private long createComputer(String name) throws Exception {
        return postJson("/api/computers", Map.of("name", name, "description", "test machine")).get("id").asLong();
    }

    private long createComputer(String name, String hostname) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("name", name);
        body.put("hostname", hostname);
        body.put("description", "test machine");
        return postJson("/api/computers", body).get("id").asLong();
    }

    private long versionOf(long computerId) throws Exception {
        return getJson("/api/computers/" + computerId).get("versions").get(0).get("id").asLong();
    }

    private long createModel(String hfId, Double sizeGiB) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("modelId", hfId);
        if (sizeGiB != null) {
            body.put("sizeGiB", sizeGiB);
        }
        return postJson("/api/models", body).get("id").asLong();
    }

    private void importText(long computerId, long versionId, long modelId, String text) throws Exception {
        postJson("/api/results/import", Map.of(
                "computerId", computerId, "versionId", versionId, "modelId", modelId, "text", text));
    }

    private void importTextWithBuild(long computerId, long versionId, long modelId, String text, String build) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("computerId", computerId);
        body.put("versionId", versionId);
        body.put("modelId", modelId);
        body.put("text", text);
        if (build != null) {
            body.put("build", build);
        }
        postJson("/api/results/import", body);
    }

    private JsonNode importAutodetect(String text) throws Exception {
        return postJson("/api/results/import", Map.of("text", text));
    }

    private String importError(String body) throws Exception {
        return mvc.perform(post("/api/results/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void importAppliesDefaultsAndFillsModelSize() throws Exception {
        long computer = createComputer("defaults-box");
        long version = versionOf(computer);
        long model = createModel("acme/Test-4B-GGUF:Q4_K_M", null);

        importText(computer, version, model, MINIMAL_TABLE);

        JsonNode r = getJson("/api/results?modelId=" + model).get("content").get(0);
        assertEquals(-1, r.get("ngl").asInt());
        assertEquals("f16", r.get("typeK").asText());
        assertEquals("f16", r.get("typeV").asText());
        assertFalse(r.get("fa").asBoolean());
        assertTrue(r.get("threads").isNull());
        assertEquals("auto", r.get("loadMode").asText());
        assertEquals(2.5, r.get("sizeGiB").asDouble(), 1e-9);

        JsonNode m = getJson("/api/models?q=Test-4B").get(0);
        assertEquals("Test-4B", m.get("name").asText());
        assertEquals("acme/Test-4B-GGUF", m.get("modelId").asText());
        assertEquals(2.5, m.get("sizeGiB").asDouble(), 1e-9);
    }

    @Test
    void sizeMismatchRequiresAcknowledgement() throws Exception {
        long computer = createComputer("mismatch-box");
        long version = versionOf(computer);
        long model = createModel("acme/Big-4B-GGUF:Q5_K_M", 99.0);

        String blocked = mvc.perform(post("/api/results/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "computerId", computer, "versionId", version, "modelId", model, "text", MINIMAL_TABLE))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        JsonNode blockedBody = json.readTree(blocked);
        assertTrue(blockedBody.get("blocked").asBoolean());
        assertEquals("SIZE_MISMATCH", blockedBody.get("warnings").get(0).get("code").asText());
    }

    @Test
    void sizeMismatchAcknowledgementImports() throws Exception {
        long computer = createComputer("ack-box");
        long version = versionOf(computer);
        long model = createModel("acme/Ack-4B-GGUF:Q5_K_M", 99.0);

        postJson("/api/results/import", Map.of(
                "computerId", computer, "versionId", version, "modelId", model,
                "text", MINIMAL_TABLE, "acknowledgeWarnings", true));

        assertEquals(1, getJson("/api/results?modelId=" + model).get("totalElements").asLong());
    }

    @Test
    void multiModelPasteIsRejected() throws Exception {
        long computer = createComputer("multi-model-box");
        long version = versionOf(computer);
        long model = createModel("acme/Multi-4B-GGUF:Q4_K_M", null);
        String text = TestResources.readSample("test-multi-model-sections.txt");

        String error = mvc.perform(post("/api/results/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "computerId", computer, "versionId", version, "modelId", model, "text", text))))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
        assertTrue(json.readTree(error).get("error").asText().contains("multiple models"));
    }

    @Test
    void sampleFileImportsEndToEnd() throws Exception {
        long computer = createComputer("e2e-box");
        long version = versionOf(computer);
        long model = createModel("acme/E2E-4B-GGUF:Q4_K_M", null);
        String text = TestResources.readSample("test-multi-model-sections.txt");
        String section = text.split("(?m)^---\\s*$")[0];

        importText(computer, version, model, section);

        assertEquals(1, getJson("/api/results?computerId=" + computer).get("totalElements").asLong());
    }

    @Test
    void importStoresBuildFromPasteLineAndRequestBody() throws Exception {
        long computer = createComputer("build-box");
        long version = versionOf(computer);
        long model = createModel("acme/Build-4B-GGUF:Q4_K_M", null);

        importTextWithBuild(computer, version, model, MINIMAL_TABLE + "\nbuild: aaa111 (1)\n", null);
        importTextWithBuild(computer, version, model, MINIMAL_TABLE, "bbb222 (2)");

        JsonNode results = getJson("/api/results?computerId=" + computer).get("content");
        assertEquals(2, results.size());
        // default sort is importedAt desc, so the second import comes first
        assertEquals("bbb222 (2)", results.get(0).get("build").asText());
        assertEquals("aaa111 (1)", results.get(1).get("build").asText());
    }

    @Test
    void latestBuildReturnsNewestForComputerAndBackend() throws Exception {
        long computer = createComputer("latest-build-box");
        long version = versionOf(computer);
        long model = createModel("acme/LB-4B-GGUF:Q4_K_M", null);
        importTextWithBuild(computer, version, model, MINIMAL_TABLE + "\nbuild: old (1)\n", null);
        importTextWithBuild(computer, version, model, MINIMAL_TABLE + "\nbuild: new (2)\n", null);

        assertEquals("new (2)", getJson("/api/results/latest-build?computerId=" + computer).get("build").asText());
        assertEquals("new (2)", getJson("/api/results/latest-build?computerId=" + computer + "&backend=CPU").get("build").asText());

        mvc.perform(get("/api/results/latest-build?computerId=" + computer + "&backend=Vulkan"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteResultRemovesItAnd404sWhenUnknown() throws Exception {
        long computer = createComputer("delete-box");
        long version = versionOf(computer);
        long model = createModel("acme/Del-4B-GGUF:Q4_K_M", null);
        importText(computer, version, model, MINIMAL_TABLE);

        JsonNode r = getJson("/api/results?computerId=" + computer).get("content").get(0);
        mvc.perform(delete("/api/results/" + r.get("id").asLong()))
                .andExpect(status().isNoContent());
        assertEquals(0, getJson("/api/results?computerId=" + computer).get("totalElements").asLong());

        mvc.perform(delete("/api/results/" + r.get("id").asLong()))
                .andExpect(status().isNotFound());
    }

    @Test
    void v5MigrationDroppedComputeColumn() {
        // Guards that Flyway actually discovers and runs the Java migration.
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'RESULT'", String.class);
        assertFalse(columns.contains("COMPUTE"));
        assertTrue(columns.contains("DEVICES"));
    }

    private JsonNode putJson(String path, Object body) throws Exception {
        String response = mvc.perform(put(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body instanceof String s ? s : json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private long resultId(long computerId) throws Exception {
        return getJson("/api/results?computerId=" + computerId).get("content").get(0).get("id").asLong();
    }

    @Test
    void editResultFixesNullDevices() throws Exception {
        long computer = createComputer("edit-devices-box");
        long version = versionOf(computer);
        long model = createModel("acme/EDV-4B-GGUF:Q4_K_M", null);

        String ambiguous = """
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | m 4B Q4 | 2.5 GiB | Vulkan,OPENVINO | pp512 | 33.4 ± 24.0 |
                | m 4B Q4 | 2.5 GiB | Vulkan,OPENVINO | tg128 | 7.3 ± 1.0 |
                """;
        postJson("/api/results/import", Map.of(
                "computerId", computer, "versionId", version, "modelId", model,
                "text", ambiguous, "acknowledgeWarnings", true));
        long id = resultId(computer);

        JsonNode updated = putJson("/api/results/" + id, Map.of("devices", "Vulkan0", "backend", "Vulkan"));
        assertEquals("Vulkan0", updated.get("devices").asText());
        assertEquals("Vulkan", updated.get("backend").asText());

        assertEquals(1, getJson("/api/results?computerId=" + computer + "&devices=Vulkan0").get("totalElements").asLong());
        List<String> values = new ArrayList<>();
        for (JsonNode v : getJson("/api/results/device-values?computerId=" + computer)) {
            values.add(v.asText());
        }
        assertTrue(values.contains("Vulkan0"));
    }

    @Test
    void devicesEmptyFilterMatchesNullDevicesOnly() throws Exception {
        long computer = createComputer("edit-empty-box");
        long version = versionOf(computer);
        long model = createModel("acme/EE-4B-GGUF:Q4_K_M", null);

        String ambiguous = """
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | m 4B Q4 | 2.5 GiB | Vulkan,OPENVINO | pp512 | 33.4 ± 24.0 |
                | m 4B Q4 | 2.5 GiB | Vulkan,OPENVINO | tg128 | 7.3 ± 1.0 |
                """;
        postJson("/api/results/import", Map.of(
                "computerId", computer, "versionId", version, "modelId", model,
                "text", ambiguous, "acknowledgeWarnings", true));
        importText(computer, version, model, VULKAN_DEV_TABLE);

        assertEquals(1, getJson("/api/results?computerId=" + computer + "&devicesEmpty=true").get("totalElements").asLong());
        assertEquals(0, getJson("/api/results?computerId=" + computer + "&devices=Vulkan0&devicesEmpty=true").get("totalElements").asLong());
        assertEquals(1, getJson("/api/results?computerId=" + computer + "&devices=Vulkan0").get("totalElements").asLong());
    }

    @Test
    void editResultPartialUpdateLeavesOtherFieldsUntouched() throws Exception {
        long computer = createComputer("edit-partial-box");
        long version = versionOf(computer);
        long model = createModel("acme/EP-4B-GGUF:Q4_K_M", null);
        importText(computer, version, model, VULKAN_DEV_TABLE); // devices Vulkan0, backend Vulkan, build null
        long id = resultId(computer);

        JsonNode updated = putJson("/api/results/" + id, Map.of("build", "abc123"));
        assertEquals("abc123", updated.get("build").asText());
        assertEquals("Vulkan0", updated.get("devices").asText());
        assertEquals("Vulkan", updated.get("backend").asText());
        assertEquals(-1, updated.get("ngl").asInt());
    }

    @Test
    void editResultBlankClearsNullableFields() throws Exception {
        long computer = createComputer("edit-clear-box");
        long version = versionOf(computer);
        long model = createModel("acme/ECL-4B-GGUF:Q4_K_M", null);
        importText(computer, version, model, VULKAN_DEV_TABLE);
        long id = resultId(computer);

        putJson("/api/results/" + id, Map.of("backend", "", "threads", "8"));
        JsonNode r = getJson("/api/results?computerId=" + computer).get("content").get(0);
        assertTrue(r.get("backend").isNull());
        assertEquals(8, r.get("threads").asInt());

        putJson("/api/results/" + id, Map.of("threads", "", "build", ""));
        r = getJson("/api/results?computerId=" + computer).get("content").get(0);
        assertTrue(r.get("threads").isNull());
        assertTrue(r.get("build").isNull());
    }

    @Test
    void editResultUpdatesImportedAt() throws Exception {
        long computer = createComputer("edit-date-box");
        long version = versionOf(computer);
        long model = createModel("acme/ED-4B-GGUF:Q4_K_M", null);
        importText(computer, version, model, MINIMAL_TABLE);
        long id = resultId(computer);

        JsonNode updated = putJson("/api/results/" + id, Map.of("importedAt", "2020-01-02T03:04:05Z"));
        assertEquals("2020-01-02T03:04:05Z", updated.get("importedAt").asText());
    }

    @Test
    void editResultRelinksComputerVersionAndModel() throws Exception {
        long a = createComputer("relink-a-box");
        long b = createComputer("relink-b-box");
        postJson("/api/computers/" + b + "/versions", Map.of("description", "b-v2"));
        long bNewest = versionOf(b);
        long m1 = createModel("acme/RL1-4B-GGUF:Q4_K_M", null);
        long m2 = createModel("acme/RL2-4B-GGUF:Q5_K_S", null);

        importText(a, versionOf(a), m1, MINIMAL_TABLE);
        long id = resultId(a);

        JsonNode updated = putJson("/api/results/" + id, Map.of(
                "computerId", b, "versionId", bNewest, "modelId", m2));
        assertEquals("relink-b-box", updated.get("computerName").asText());
        assertEquals("RL2-4B", updated.get("modelName").asText());
        assertEquals(bNewest, updated.get("versionId").asLong());

        assertEquals(0, getJson("/api/results?computerId=" + a).get("totalElements").asLong());
        assertEquals(1, getJson("/api/results?computerId=" + b).get("totalElements").asLong());

        // computerId without versionId: attach to the newest version of that computer
        long c = createComputer("relink-c-box");
        putJson("/api/results/" + id, Map.of("computerId", c));
        assertEquals(1, getJson("/api/results?computerId=" + c).get("totalElements").asLong());
    }

    @Test
    void editResultReplacesParams() throws Exception {
        long computer = createComputer("edit-params-box");
        long version = versionOf(computer);
        long model = createModel("acme/EPAR-4B-GGUF:Q4_K_M", null);

        String withParam = """
                | model | size | backend | test | t/s | n_batch |
                | ----- | ----: | ------- | ---: | --: | ------: |
                | m 4B Q4 | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 | 8 |
                | m 4B Q4 | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 | 8 |
                """;
        importText(computer, version, model, withParam);
        long id = resultId(computer);

        JsonNode r = getJson("/api/results?computerId=" + computer).get("content").get(0);
        assertEquals("8", r.get("params").get("n_batch").asText());

        putJson("/api/results/" + id, Map.of("params", Map.of("n_batch", "16", "n_ctx", "4096")));
        r = getJson("/api/results?computerId=" + computer).get("content").get(0);
        assertEquals("16", r.get("params").get("n_batch").asText());
        assertEquals("4096", r.get("params").get("n_ctx").asText());

        putJson("/api/results/" + id, Map.of("params", Map.of()));
        r = getJson("/api/results?computerId=" + computer).get("content").get(0);
        assertEquals(0, r.get("params").size());
    }

    @Test
    void editResultAcceptsJsonNumbersForNumericFields() throws Exception {
        long computer = createComputer("edit-num-box");
        long version = versionOf(computer);
        long model = createModel("acme/EN-4B-GGUF:Q4_K_M", null);
        importText(computer, version, model, MINIMAL_TABLE);
        long id = resultId(computer);

        JsonNode updated = putJson("/api/results/" + id,
                "{\"ngl\": 0, \"threads\": 12, \"sizeGiBObserved\": 2.5}");
        assertEquals(0, updated.get("ngl").asInt());
        assertEquals(12, updated.get("threads").asInt());
        assertEquals(2.5, updated.get("sizeGiB").asDouble(), 1e-9);
    }

    @Test
    void editResultRejectsInvalidUpdates() throws Exception {
        long computer = createComputer("edit-invalid-box");
        long version = versionOf(computer);
        long model = createModel("acme/EI-4B-GGUF:Q4_K_M", null);
        importText(computer, version, model, MINIMAL_TABLE);
        long id = resultId(computer);

        mvc.perform(put("/api/results/999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("devices", "CPU"))))
                .andExpect(status().isNotFound());

        for (String body : List.of(
                json.writeValueAsString(Map.of("ngl", "abc")),
                json.writeValueAsString(Map.of("ngl", "-2")),
                json.writeValueAsString(Map.of("typeK", "")),
                json.writeValueAsString(Map.of("threads", "0")),
                json.writeValueAsString(Map.of("importedAt", "not-a-date")))) {
            mvc.perform(put("/api/results/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        long other = createComputer("edit-invalid-other-box");
        mvc.perform(put("/api/results/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("computerId", other, "versionId", version))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importWithoutVersionUsesNewest() throws Exception {
        long computer = createComputer("newest-version-box");
        long model = createModel("acme/NV-4B-GGUF:Q4_K_M", null);
        postJson("/api/computers/" + computer + "/versions", Map.of("description", "v2"));

        postJson("/api/results/import", Map.of(
                "computerId", computer, "modelId", model, "text", MINIMAL_TABLE));

        JsonNode r = getJson("/api/results?computerId=" + computer).get("content").get(0);
        JsonNode newest = getJson("/api/computers/" + computer).get("versions").get(0);
        assertEquals(newest.get("createdAt").asText(), r.get("versionDate").asText());
    }

    @Test
    void spaForwardAndApi404() throws Exception {
        // MockMvc does not execute forwards; assert the recorded forward target.
        mvc.perform(get("/computers/123"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));

        String index = mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(index.contains("test-spa-index"));

        mvc.perform(get("/api/nope")).andExpect(status().isNotFound());
    }

    @Test
    void exportImportRoundTrip() throws Exception {
        long computer = createComputer("roundtrip-box", "rt-host");
        long version = versionOf(computer);
        long model = createModel("acme/RT-4B-GGUF:Q4_K_M", null);
        importText(computer, version, model, MINIMAL_TABLE);

        JsonNode export = getJson("/api/export");
        int nResults = export.get("results").size();
        assertTrue(nResults >= 1);

        JsonNode imported = postJson("/api/import", export);
        assertEquals(nResults, imported.get("results").asInt());

        assertEquals(nResults, getJson("/api/results?size=100").get("totalElements").asLong());

        JsonNode computers = getJson("/api/computers");
        JsonNode rt = null;
        for (JsonNode c : computers) {
            if ("rt-host".equals(c.get("hostname").asText())) {
                rt = c;
            }
        }
        assertTrue(rt != null, "computer with hostname rt-host missing after round trip");
    }

    @Test
    void autodetectResolvesComputerAndCreatesModelSilently() throws Exception {
        long computer = createComputer("auto-box", "auto-box");

        JsonNode res = importAutodetect(autoDetectPaste("auto-box", "-hf acme/Auto-4B-GGUF:Q4_K_M"));
        assertEquals(1, res.get("results").size());
        assertEquals("auto-box", res.get("results").get(0).get("computerName").asText());
        assertEquals("acme/Auto-4B-GGUF", res.get("results").get(0).get("modelId").asText());

        assertEquals(1, getJson("/api/results?computerId=" + computer).get("totalElements").asLong());
        JsonNode m = getJson("/api/models?q=Auto-4B").get(0);
        assertEquals("Auto-4B", m.get("name").asText());
        assertEquals("acme/Auto-4B-GGUF", m.get("modelId").asText());
        assertEquals("Q4_K_M", m.get("quantization").asText());
    }

    @Test
    void autodetectMultiModelPasteLinksEachRunToItsModel() throws Exception {
        long computer = createComputer("twina-box", "twina-box");
        long known = createModel("acme/TwinA-4B-GGUF:Q4_K_M", null);

        JsonNode res = importAutodetect(twoRunsPaste("twina-box", "acme/TwinA-4B-GGUF:Q4_K_M", "acme/TwinA-2B-GGUF:Q4_K_M"));
        assertEquals(2, res.get("results").size());
        assertEquals(2, getJson("/api/results?computerId=" + computer).get("totalElements").asLong());

        long unknown = getJson("/api/models?q=TwinA-2B").get(0).get("id").asLong();
        assertEquals(1, getJson("/api/results?modelId=" + known).get("totalElements").asLong());
        assertEquals(1, getJson("/api/results?modelId=" + unknown).get("totalElements").asLong());
    }

    @Test
    void listFiltersByBaseModelAcrossQuantsAndUploaders() throws Exception {
        long computer = createComputer("filter-box");
        long version = versionOf(computer);
        long q4 = createModel("acme/Filter-4B-GGUF:Q4_K_M", null);
        long q5 = createModel("acme/Filter-4B-GGUF:Q5_K_S", null);
        long twin = createModel("otherup/Filter-4B-GGUF:Q5_K_S", null);
        long other = createModel("acme/Other-4B-GGUF:Q4_K_M", null);

        importText(computer, version, q4, MINIMAL_TABLE);
        importText(computer, version, q5, MINIMAL_TABLE);
        importText(computer, version, twin, MINIMAL_TABLE);
        importText(computer, version, other, MINIMAL_TABLE);

        // The base name covers every uploader and quant of the model.
        assertEquals(3, getJson("/api/results?model=Filter-4B").get("totalElements").asLong());
        // A full repo id still selects only that uploader's results.
        assertEquals(2, getJson("/api/results?model=acme/Filter-4B-GGUF").get("totalElements").asLong());
        assertEquals(1, getJson("/api/results?model=acme/Other-4B-GGUF").get("totalElements").asLong());
        assertEquals(0, getJson("/api/results?model=acme/Missing-4B-GGUF").get("totalElements").asLong());
    }

    @Test
    void hostnameCollisionPicksNewestVersion() throws Exception {
        createComputer("retired-box", "shared-host");
        long current = createComputer("current-box", "shared-host");
        postJson("/api/computers/" + current + "/versions", Map.of("description", "v2"));

        importAutodetect(autoDetectPaste("shared-host", "-hf acme/Collide-4B-GGUF:Q4_K_M"));

        assertEquals(1, getJson("/api/results?computerId=" + current).get("totalElements").asLong());
    }

    @Test
    void autodetectUnknownHostnameIsBlocked() throws Exception {
        createComputer("other-box", "other-host");

        String error = importError(json.writeValueAsString(
                Map.of("text", autoDetectPaste("unknown-host", "-hf acme/Auto-4B-GGUF:Q4_K_M"))));
        assertTrue(json.readTree(error).get("error").asText().contains("no computer with hostname 'unknown-host'"));
    }

    @Test
    void autodetectWithoutHfIsBlocked() throws Exception {
        createComputer("nohf-box", "nohf-box");

        String error = importError(json.writeValueAsString(
                Map.of("text", autoDetectPaste("nohf-box", "--model /models/auto.gguf"))));
        assertTrue(json.readTree(error).get("error").asText().contains("no -hf parameter"));
    }

    @Test
    void autodetectWithoutCommandLineIsBlocked() throws Exception {
        String error = importError(json.writeValueAsString(Map.of("text", MINIMAL_TABLE)));
        assertTrue(json.readTree(error).get("error").asText().contains("no hostname detected"));
    }

    @Test
    void autodetectHfWithoutQuantIsBlocked() throws Exception {
        createComputer("noquant-box", "noquant-box");

        String error = importError(json.writeValueAsString(
                Map.of("text", autoDetectPaste("noquant-box", "-hf acme/Auto-4B-GGUF"))));
        assertTrue(json.readTree(error).get("error").asText().contains("no quantization"));
    }

    @Test
    void explicitModelMultiModelPasteStillRejected() throws Exception {
        long computer = createComputer("twinb-box", "twinb-box");
        long version = versionOf(computer);
        long model = createModel("acme/TwinB-4B-GGUF:Q4_K_M", null);

        String error = importError(json.writeValueAsString(Map.of(
                "computerId", computer, "versionId", version, "modelId", model,
                "text", twoRunsPaste("twinb-box", "acme/TwinB-4B-GGUF:Q4_K_M", "acme/TwinB-2B-GGUF:Q4_K_M"))));
        assertTrue(json.readTree(error).get("error").asText().contains("multiple models"));
    }

    @Test
    void detectEndpointReturnsPerRunAttribution() throws Exception {
        String response = mvc.perform(post("/api/results/detect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "text", twoRunsPaste("detect-box", "acme/Detect-4B-GGUF:Q4_K_M", "acme/Detect-2B-GGUF:Q4_K_M")))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = json.readTree(response);
        assertTrue(body.get("parseError").isNull());
        assertEquals(2, body.get("runs").size());
        assertEquals("detect-box", body.get("runs").get(0).get("hostname").asText());
        assertEquals("acme/Detect-4B-GGUF:Q4_K_M", body.get("runs").get(0).get("hfModelId").asText());
        assertEquals("acme/Detect-2B-GGUF:Q4_K_M", body.get("runs").get(1).get("hfModelId").asText());

        String garbage = mvc.perform(post("/api/results/detect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "no tables here"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(json.readTree(garbage).get("parseError").asText().contains("no result data"));
    }

    private static final String VULKAN_DEV_TABLE = """
            | model | size | backend | dev | test | t/s |
            | ----- | ----: | ------- | ---: | ---: | --: |
            | m 4B Q4 | 2.5 GiB | Vulkan | Vulkan0 | pp512 | 33.4 ± 24.0 |
            | m 4B Q4 | 2.5 GiB | Vulkan | Vulkan0 | tg128 | 7.3 ± 1.0 |
            """;

    private static final String VULKAN_INFERRED_TABLE = """
            | model                          |       size | backend | ngl |  n_cpu_moe |          ts |            test |                  t/s |
            | ------------------------------ | ---------: | ------- | --: | ---------: | ----------: | --------------: | -------------------: |
            | m 4B Q4_K - Medium             |     2.5 GiB | Vulkan  |  99 |         26 | 36.00/13.00 |           pp512 |        43.91 ± 0.73 |
            | m 4B Q4_K - Medium             |     2.5 GiB | Vulkan  |  99 |         26 | 36.00/13.00 |           tg128 |          9.87 ± 0.06 |
            """;

    @Test
    void importResolvesDevicesFromDevColumnAndInference() throws Exception {
        long computer = createComputer("devices-box");
        long version = versionOf(computer);
        long model = createModel("acme/Dev-4B-GGUF:Q4_K_M", null);

        importText(computer, version, model, VULKAN_DEV_TABLE);
        importText(computer, version, model, VULKAN_INFERRED_TABLE);
        importText(computer, version, model, MINIMAL_TABLE); // backend CPU, no dev column

        assertEquals(1, getJson("/api/results?computerId=" + computer + "&devices=Vulkan0").get("totalElements").asLong());
        assertEquals(1, getJson("/api/results?computerId=" + computer + "&devices=Vulkan0,Vulkan1,CPU").get("totalElements").asLong());
        assertEquals(1, getJson("/api/results?computerId=" + computer + "&devices=CPU").get("totalElements").asLong());

        // the raw dev column value is preserved in params; rows without a dev
        // column have no "dev" entry
        JsonNode rows = getJson("/api/results?computerId=" + computer + "&size=10").get("content");
        for (JsonNode r : rows) {
            if ("Vulkan0".equals(r.get("devices").asText())) {
                assertEquals("Vulkan0", r.get("params").get("dev").asText());
            } else {
                assertTrue(r.get("params").get("dev") == null);
            }
        }
    }

    @Test
    void deviceValuesEndpointListsDistinctSortedValues() throws Exception {
        long computer = createComputer("dvalues-box");
        long version = versionOf(computer);
        long model = createModel("acme/DV-4B-GGUF:Q4_K_M", null);

        importText(computer, version, model, MINIMAL_TABLE);
        importText(computer, version, model, VULKAN_DEV_TABLE);

        JsonNode values = getJson("/api/results/device-values?computerId=" + computer);
        List<String> list = new ArrayList<>();
        for (JsonNode v : values) {
            list.add(v.asText());
        }
        assertEquals(List.of("CPU", "Vulkan0"), list);
    }

    @Test
    void inconclusiveDevicesRequiresAcknowledgement() throws Exception {
        long computer = createComputer("dunk-box");
        long version = versionOf(computer);
        long model = createModel("acme/DU-4B-GGUF:Q4_K_M", null);

        String ambiguous = """
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | m 4B Q4 | 2.5 GiB | Vulkan,OPENVINO | pp512 | 33.4 ± 24.0 |
                | m 4B Q4 | 2.5 GiB | Vulkan,OPENVINO | tg128 | 7.3 ± 1.0 |
                """;

        String blocked = mvc.perform(post("/api/results/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "computerId", computer, "versionId", version, "modelId", model, "text", ambiguous))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        JsonNode blockedBody = json.readTree(blocked);
        assertTrue(blockedBody.get("blocked").asBoolean());
        assertEquals("DEVICES_UNKNOWN", blockedBody.get("warnings").get(0).get("code").asText());

        postJson("/api/results/import", Map.of(
                "computerId", computer, "versionId", version, "modelId", model,
                "text", ambiguous, "acknowledgeWarnings", true));
        assertTrue(getJson("/api/results?computerId=" + computer).get("content").get(0).get("devices").isNull());
    }

    @Test
    void hardwareMismatchWarnsButDoesNotModifyVersion() throws Exception {
        long computer = createComputer("hw-box");
        postJson("/api/computers/" + computer + "/versions", Map.of(
                "description", "v2",
                "devices", Map.of("Vulkan0", "AMD Radeon RX 7800 XT")));
        long version = versionOf(computer); // newest = v2
        long model = createModel("acme/HW-4B-GGUF:Q4_K_M", null);

        String withDump = """
                ggml_vulkan: Found 1 Vulkan devices:
                ggml_vulkan: 0 = AMD Radeon RX 7900 XTX (RADV NAVI31) (radv) | uma: 0
                """ + VULKAN_DEV_TABLE;

        JsonNode res = postJson("/api/results/import", Map.of(
                "computerId", computer, "versionId", version, "modelId", model, "text", withDump));
        assertEquals(1, res.get("results").size());
        assertEquals("HARDWARE_MISMATCH", res.get("warnings").get(0).get("code").asText());

        // stored hardware unchanged and no new version was created
        JsonNode detail = getJson("/api/computers/" + computer);
        assertEquals(2, detail.get("versions").size());
        assertEquals("AMD Radeon RX 7800 XT", detail.get("versions").get(0).get("devices").get("Vulkan0").asText());
    }

    @Test
    void matchingHardwareDoesNotWarn() throws Exception {
        long computer = createComputer("hwok-box");
        postJson("/api/computers/" + computer + "/versions", Map.of(
                "description", "v2",
                "devices", Map.of("Vulkan0", "AMD Radeon RX 7900 XTX (RADV NAVI31) (radv)")));
        long version = versionOf(computer);
        long model = createModel("acme/HWOK-4B-GGUF:Q4_K_M", null);

        String withDump = """
                ggml_vulkan: Found 1 Vulkan devices:
                ggml_vulkan: 0 = AMD Radeon RX 7900 XTX (RADV NAVI31) (radv) | uma: 0
                """ + VULKAN_DEV_TABLE;

        JsonNode res = postJson("/api/results/import", Map.of(
                "computerId", computer, "versionId", version, "modelId", model, "text", withDump));
        assertEquals(1, res.get("results").size());
        assertEquals(0, res.get("warnings").size());
    }

    @Test
    void cpuOnlyRunDoesNotWarnAboutGpuHardware() throws Exception {
        long computer = createComputer("hwcpu-box");
        postJson("/api/computers/" + computer + "/versions", Map.of(
                "description", "v2",
                "devices", Map.of("Vulkan0", "AMD Radeon RX 7900 XTX")));
        long version = versionOf(computer);
        long model = createModel("acme/HWCPU-4B-GGUF:Q4_K_M", null);

        JsonNode res = postJson("/api/results/import", Map.of(
                "computerId", computer, "versionId", version, "modelId", model, "text", MINIMAL_TABLE));
        assertEquals(1, res.get("results").size());
        assertEquals(0, res.get("warnings").size());
    }

    @Test
    void versionDevicesCanBeCreatedUpdatedAndPreserved() throws Exception {
        long computer = createComputer("vdev-box");
        JsonNode created = postJson("/api/computers/" + computer + "/versions", Map.of(
                "description", "with hardware",
                "devices", Map.of("CPU", "AMD Ryzen 9 7950X", "MEM", "48GB DDR4-3200",
                        "Vulkan0", "AMD Radeon RX 7900 XTX", "blank", "")));
        long version = created.get("id").asLong();
        assertEquals("AMD Ryzen 9 7950X", created.get("devices").get("CPU").asText());
        assertNull(created.get("devices").get("blank")); // blank values are dropped

        mvc.perform(put("/api/computers/" + computer + "/versions/" + version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "description", "",
                                "devices", Map.of("CPU", "AMD Ryzen 9 7950X", "Vulkan0", "AMD Radeon RX 7800 XT")))))
                .andExpect(status().isOk());
        JsonNode v = getJson("/api/computers/" + computer).get("versions").get(0);
        assertTrue(v.get("description").isNull()); // blank clears the description
        assertEquals("AMD Radeon RX 7800 XT", v.get("devices").get("Vulkan0").asText());

        // null devices = unchanged
        mvc.perform(put("/api/computers/" + computer + "/versions/" + version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("description", "kept"))))
                .andExpect(status().isOk());
        v = getJson("/api/computers/" + computer).get("versions").get(0);
        assertEquals("AMD Radeon RX 7800 XT", v.get("devices").get("Vulkan0").asText());
    }

    @Test
    void exportRoundTripPreservesDevicesAndVersionDevices() throws Exception {
        long computer = createComputer("rt2-box", "rt2-host");
        postJson("/api/computers/" + computer + "/versions", Map.of(
                "description", "hw", "devices", Map.of("Vulkan0", "AMD Radeon RX 7900 XTX")));
        long version = versionOf(computer);
        long model = createModel("acme/RT2-4B-GGUF:Q4_K_M", null);
        importText(computer, version, model, MINIMAL_TABLE); // devices CPU

        JsonNode export = getJson("/api/export");
        postJson("/api/import", export);

        boolean devicesSurvived = false;
        for (JsonNode r : getJson("/api/results?size=100").get("content")) {
            if ("CPU".equals(r.get("devices").asText())) {
                devicesSurvived = true;
            }
        }
        assertTrue(devicesSurvived);

        JsonNode rt = null;
        for (JsonNode c : getJson("/api/computers")) {
            if ("rt2-host".equals(c.get("hostname").asText())) {
                rt = c;
            }
        }
        assertTrue(rt != null, "computer with hostname rt2-host missing after round trip");
        assertEquals("AMD Radeon RX 7900 XTX", getJson("/api/computers/" + rt.get("id").asLong())
                .get("versions").get(0).get("devices").get("Vulkan0").asText());
    }

    @Test
    void computerHostnameCanBeCreatedAndUpdated() throws Exception {
        long id = createComputer("box", "box-host");
        assertEquals("box-host", getJson("/api/computers/" + id).get("hostname").asText());

        mvc.perform(put("/api/computers/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "box", "hostname", "renamed-host"))))
                .andExpect(status().isOk());
        assertEquals("renamed-host", getJson("/api/computers/" + id).get("hostname").asText());

        // blank clears the hostname
        mvc.perform(put("/api/computers/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "box", "hostname", ""))))
                .andExpect(status().isOk());
        assertTrue(getJson("/api/computers/" + id).get("hostname").isNull());
    }
}
