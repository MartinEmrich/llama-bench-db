package llamabendb.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private static final String MINIMAL_TABLE = """
            | model | size | backend | test | t/s |
            | ----- | ----: | ------- | ---: | --: |
            | m 4B Q4 | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |
            | m 4B Q4 | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |
            """;

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
        String text = Files.readString(Path.of("samples", "surfacego.txt"));

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
        long model = createModel("unsloth/Qwen3.5-4B-GGUF:Q4_K_M", null);
        String text = Files.readString(Path.of("samples", "surfacego.txt"));
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
        long computer = createComputer("roundtrip-box");
        long version = versionOf(computer);
        long model = createModel("acme/RT-4B-GGUF:Q4_K_M", null);
        importText(computer, version, model, MINIMAL_TABLE);

        JsonNode export = getJson("/api/export");
        int nResults = export.get("results").size();
        assertTrue(nResults >= 1);

        JsonNode imported = postJson("/api/import", export);
        assertEquals(nResults, imported.get("results").asInt());

        assertEquals(nResults, getJson("/api/results?size=100").get("totalElements").asLong());
    }
}
