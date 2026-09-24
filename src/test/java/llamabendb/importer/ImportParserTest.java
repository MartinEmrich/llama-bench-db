package llamabendb.importer;

import llamabendb.TestResources;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportParserTest {

    private final ImportParser parser = new ImportParser();

    private String sample(String name) throws IOException {
        return TestResources.readSample(name);
    }

    @Test
    void multiModelSectionsParseWithPerRunAttribution() throws IOException {
        // A multi-model paste is no longer rejected by the parser; each run keeps
        // the hostname and -hf id of the command line preceding its table. The
        // third section has no command line of its own, so it inherits the second one.
        ImportParser.ParseResult r = parser.parse(sample("test-multi-model-sections.txt"));
        assertEquals(3, r.datasets().size());
        assertEquals(3, r.modelStrings().size());

        ImportParser.Dataset first = r.datasets().get(0);
        assertEquals("moonspire 4B Q4_K - Medium", first.modelString());
        assertEquals("test", first.hostname());
        assertEquals("test/moonspire-4B-GGUF:Q4_K_M", first.hfModelId());

        ImportParser.Dataset second = r.datasets().get(1);
        assertEquals("moonspire 2B Q4_K - Medium", second.modelString());
        assertEquals("test", second.hostname());
        assertEquals("test/moonspire-2B-GGUF:Q4_K_M", second.hfModelId());

        ImportParser.Dataset third = r.datasets().get(2);
        assertEquals("moonspire 0.8B Q8_0", third.modelString());
        assertEquals("test", third.hostname());
        assertEquals("test/moonspire-2B-GGUF:Q4_K_M", third.hfModelId());
    }

    @Test
    void multiModelSectionsParseToSingleDatasets() throws IOException {
        String[] sections = sample("test-multi-model-sections.txt").split("(?m)^---\\s*$");
        ImportParser.ParseResult r = parser.parse(sections[0]);
        assertEquals(1, r.datasets().size());
        ImportParser.Dataset d = r.datasets().get(0);
        assertEquals("moonspire 4B Q4_K - Medium", d.modelString());
        assertEquals(2.54, d.sizeGiB(), 1e-9);
        assertEquals(512, d.ppTokens());
        assertEquals(128, d.tgTokens());
        assertEquals(0.71, d.ppTps(), 1e-9);
        assertEquals(0.0, d.ppDeviation(), 1e-9);
        assertEquals(0.58, d.tgTps(), 1e-9);
        assertEquals("none", d.fields().get("lm"));
        assertEquals("2", d.fields().get("threads"));
        assertEquals("q8_0", d.fields().get("type_k"));

        // third section uses MiB -> normalized to GiB
        ImportParser.ParseResult r3 = parser.parse(sections[2]);
        assertEquals(763.78 / 1024.0, r3.datasets().get(0).sizeGiB(), 1e-9);
    }

    @Test
    void noiseFileSkipsEmptyTablesAndCapturesUnknownColumns() throws IOException {
        ImportParser.ParseResult r = parser.parse(sample("test-noise-and-unknown-columns.txt"));
        assertEquals(3, r.datasets().size());
        assertEquals(1, r.modelStrings().size());
        assertEquals("dragonspine A3B IQ3_XXS - 3.0625 bpw", r.modelStrings().get(0));

        long withNcmoe = r.datasets().stream().filter(d -> d.fields().containsKey("n_cpu_moe")).count();
        assertEquals(2, withNcmoe);

        ImportParser.Dataset first = r.datasets().get(0);
        assertEquals("off", first.fields().get("lazy_mode"));
        assertFalse(first.fields().containsKey("n_cpu_moe"));
        assertEquals("30.00/20.00", first.fields().get("ts"));
        assertEquals("30", first.fields().get("ngl"));
    }

    @Test
    void deviceDumpsAreExtractedPerTable() throws IOException {
        ImportParser.ParseResult r = parser.parse(sample("test-noise-and-unknown-columns.txt"));
        for (ImportParser.Dataset d : r.datasets()) {
            assertEquals(2, d.deviceDumps().size());
            assertEquals("Vulkan", d.deviceDumps().get(0).framework());
            assertEquals(0, d.deviceDumps().get(0).index());
            assertTrue(d.deviceDumps().get(0).name().startsWith("AMD Radeon RX 7900 XTX"));
            assertEquals(1, d.deviceDumps().get(1).index());
            assertTrue(d.deviceDumps().get(1).name().startsWith("AMD Radeon RX 7800 XT"));
            assertNull(d.openvinoType());
        }
    }

    @Test
    void openvinoDumpAndUsingDeviceLineAreCaptured() {
        String text = """
                a@box:~$ llama-bench -hf test/M-GGUF:Q4_K_M
                ggml_openvino: Found 1 OpenVINO devices:
                ggml_openvino: 0 = Intel(R) Arc(TM) A770 Graphics | subdevice: 0
                OpenVINO: using device NPU
                | model | size | backend | dev | test | t/s |
                | ----- | ----: | ------- | ---: | ---: | --: |
                | m 4B Q4 | 2.5 GiB | OpenVINO | OPENVINO0 | pp512 | 0.7 ± 0.0 |
                | m 4B Q4 | 2.5 GiB | OpenVINO | OPENVINO0 | tg128 | 0.6 ± 0.1 |
                """;
        ImportParser.Dataset d = parser.parse(text).datasets().get(0);
        assertEquals(1, d.deviceDumps().size());
        assertEquals("OpenVINO", d.deviceDumps().get(0).framework());
        assertEquals(0, d.deviceDumps().get(0).index());
        assertEquals("Intel(R) Arc(TM) A770 Graphics", d.deviceDumps().get(0).name());
        assertEquals("NPU", d.openvinoType());
    }

    @Test
    void multiModelRejectionFileParsesWithPerRunHfIds() throws IOException {
        ImportParser.ParseResult r = parser.parse(sample("test-multi-model-rejection.txt"));
        assertEquals(3, r.datasets().size());
        assertEquals(2, r.modelStrings().size());
        // first prompt is a bare "$" without user@host
        assertNull(r.datasets().get(0).hostname());
        assertEquals("test/moonspire-27B-GGUF:TQ1_0", r.datasets().get(0).hfModelId());
        assertEquals("test", r.datasets().get(1).hostname());
        assertEquals("test/moonspire-27B-GGUF:Q2_0", r.datasets().get(1).hfModelId());
        assertEquals("test/moonspire-27B-GGUF:Q2_0", r.datasets().get(2).hfModelId());
    }

    @Test
    void commandLineBeforeTableIsAttributed() {
        String text = """
                martin@martinssurfacego:~/upstream/llama.cpp$ build/bin/llama-bench -hf unsloth/Qwen3.5-4B-GGUF:Q4_K_M -ctk q8_0
                Downloading Qwen3.5-4B-Q4_K_M.gguf ───────────────────────────────── 100%
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | qwen35 4B Q4_K - Medium | 2.54 GiB | CPU | pp512 | 0.71 ± 0.00 |
                | qwen35 4B Q4_K - Medium | 2.54 GiB | CPU | tg128 | 0.58 ± 0.00 |
                """;
        ImportParser.Dataset d = parser.parse(text).datasets().get(0);
        assertEquals("martinssurfacego", d.hostname());
        assertEquals("unsloth/Qwen3.5-4B-GGUF:Q4_K_M", d.hfModelId());
    }

    @Test
    void tableWithoutPrecedingCommandLineHasNullAttribution() {
        String text = """
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | m 4B Q4 | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |
                | m 4B Q4 | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |
                """;
        ImportParser.Dataset d = parser.parse(text).datasets().get(0);
        assertNull(d.hostname());
        assertNull(d.hfModelId());
    }

    @Test
    void eachTableGetsTheCommandLinePrecedingIt() {
        String text = """
                a@box1:~$ llama-bench -hf test/ModelA-GGUF:Q4_K_M
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | A 4B Q4_K | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |
                | A 4B Q4_K | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |

                b@box2:~$ llama-bench -hf test/ModelB-GGUF:Q5_K_M
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | B 4B Q5_K | 3.1 GiB | CPU | pp512 | 0.8 ± 0.0 |
                | B 4B Q5_K | 3.1 GiB | CPU | tg128 | 0.7 ± 0.1 |
                """;
        ImportParser.ParseResult r = parser.parse(text);
        assertEquals("box1", r.datasets().get(0).hostname());
        assertEquals("test/ModelA-GGUF:Q4_K_M", r.datasets().get(0).hfModelId());
        assertEquals("box2", r.datasets().get(1).hostname());
        assertEquals("test/ModelB-GGUF:Q5_K_M", r.datasets().get(1).hfModelId());
    }

    @Test
    void hfFlagVariantsAreDetected() {
        String table = """
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | m 4B Q4 | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |
                | m 4B Q4 | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |
                """;
        assertEquals("test/M-GGUF:Q4_K_M", parser.parse("$ llama-bench -hf test/M-GGUF:Q4_K_M\n" + table).datasets().get(0).hfModelId());
        assertEquals("test/M-GGUF:Q4_K_M", parser.parse("$ llama-bench -hfr test/M-GGUF:Q4_K_M\n" + table).datasets().get(0).hfModelId());
        assertEquals("test/M-GGUF:Q4_K_M", parser.parse("$ llama-bench --hf-repo test/M-GGUF:Q4_K_M\n" + table).datasets().get(0).hfModelId());
        assertEquals("test/M-GGUF:Q4_K_M", parser.parse("$ llama-bench -hf=test/M-GGUF:Q4_K_M\n" + table).datasets().get(0).hfModelId());
    }

    @Test
    void commandLineWithoutHfYieldsNullModelId() {
        String text = """
                test@test:~$ llama-bench --model /home/test/models/M-UD-IQ3_XXS.gguf -ngl 30
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | m A3B IQ3_XXS | 76.3 GiB | Vulkan | pp512 | 33.4 ± 24.0 |
                | m A3B IQ3_XXS | 76.3 GiB | Vulkan | tg128 | 7.3 ± 1.0 |
                """;
        ImportParser.Dataset d = parser.parse(text).datasets().get(0);
        assertEquals("test", d.hostname());
        assertNull(d.hfModelId());
    }

    @Test
    void ansiEscapesAreStrippedForDetection() {
        String text = "\u001b[1;32martin@surfacego\u001b[0m:~\u001b[34m/llama.cpp\u001b[0m$ build/bin/llama-bench -hf test/M-GGUF:Q4_K_M\n"
                + "| model | size | backend | test | t/s |\n"
                + "| ----- | ----: | ------- | ---: | --: |\n"
                + "| m 4B Q4 | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |\n"
                + "| m 4B Q4 | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |\n";
        ImportParser.Dataset d = parser.parse(text).datasets().get(0);
        assertEquals("surfacego", d.hostname());
        assertEquals("test/M-GGUF:Q4_K_M", d.hfModelId());
    }

    @Test
    void detectCommandLinesReturnsAllInOrder() {
        String text = """
                a@box1:~$ llama-bench -hf test/A:Q4_K_M
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | A 4B Q4_K | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |
                | A 4B Q4_K | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |

                b@box2:~$ llama-bench --model /models/B.gguf
                """;
        List<ImportParser.CommandLineInfo> lines = parser.detectCommandLines(text);
        assertEquals(2, lines.size());
        assertEquals(new ImportParser.CommandLineInfo("box1", "test/A:Q4_K_M"), lines.get(0));
        assertEquals(new ImportParser.CommandLineInfo("box2", null), lines.get(1));
    }

    @Test
    void twoDatasetsOnePasteHasTwoDatasets() throws IOException {
        ImportParser.ParseResult r = parser.parse(sample("test-two-datasets-one-paste.txt"));
        assertEquals(2, r.datasets().size());
        assertEquals(1, r.modelStrings().size());
        assertTrue(r.datasets().stream().anyMatch(d -> "none".equals(d.fields().get("dev"))));
        assertTrue(r.datasets().stream().anyMatch(d -> "Vulkan0".equals(d.fields().get("dev"))));
    }

    @Test
    void missingTgRowIsRejected() {
        String text = """
                | model | size | params | backend | threads | test | t/s |
                | ----- | ----: | -----: | ------- | ------: | ---: | --: |
                | m 4B Q4 | 2.5 GiB | 4.2 B | CPU | 2 | pp512 | 0.7 ± 0.0 |
                """;
        ImportException e = assertThrows(ImportException.class, () -> parser.parse(text));
        assertTrue(e.getMessage().contains("incomplete dataset"));
    }

    @Test
    void garbageWithoutTablesIsRejected() {
        ImportException e = assertThrows(ImportException.class,
                () -> parser.parse("no tables here\njust noise\n^C\nbuild: 12345\n"));
        assertTrue(e.getMessage().contains("no result data"));
    }

    @Test
    void unknownColumnsAreKeptInFields() {
        String text = """
                | model | size | backend | ngl | batch | test | t/s |
                | ----- | ----: | ------- | --: | ----: | ---: | --: |
                | m 4B Q4 | 2.5 GiB | CPU | -1 | 2048 | pp512 | 0.7 ± 0.0 |
                | m 4B Q4 | 2.5 GiB | CPU | -1 | 2048 | tg128 | 0.6 ± 0.1 |
                """;
        ImportParser.Dataset d = parser.parse(text).datasets().get(0);
        assertEquals("2048", d.fields().get("batch"));
        assertFalse(d.fields().containsKey("test"));
        assertFalse(d.fields().containsKey("t/s"));
    }

    @Test
    void blankLinesInsideTableAreTolerated() {
        String text = """
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |

                | m 4B Q4 | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |
                | m 4B Q4 | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |

                build: 12345
                """;
        ImportParser.ParseResult r = parser.parse(text);
        assertEquals(1, r.datasets().size());
        assertEquals("12345", r.datasets().get(0).build());
    }

    @Test
    void buildLineAfterTableIsCaptured() {
        String text = """
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | m 4B Q4 | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |
                | m 4B Q4 | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |

                build: 861bd3c10 (11029)
                """;
        ImportParser.Dataset d = parser.parse(text).datasets().get(0);
        assertEquals("861bd3c10 (11029)", d.build());
    }

    @Test
    void twoTablesKeepTheirOwnBuildLines() {
        String text = """
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | m 4B Q4 | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |
                | m 4B Q4 | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |

                build: aaa111 (1)

                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | m 4B Q4 | 2.5 GiB | CPU | pp512 | 0.8 ± 0.0 |
                | m 4B Q4 | 2.5 GiB | CPU | tg128 | 0.7 ± 0.1 |

                build: bbb222 (2)
                """;
        ImportParser.ParseResult r = parser.parse(text);
        assertEquals(2, r.datasets().size());
        assertEquals("aaa111 (1)", r.datasets().get(0).build());
        assertEquals("bbb222 (2)", r.datasets().get(1).build());
    }

    @Test
    void pasteWithoutBuildLineYieldsNullBuild() {
        String text = """
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | m 4B Q4 | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |
                | m 4B Q4 | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |
                """;
        assertNull(parser.parse(text).datasets().get(0).build());
    }

    @Test
    void buildLineBeforeAnyTableIsIgnored() {
        String text = """
                build: orphan (0)
                | model | size | backend | test | t/s |
                | ----- | ----: | ------- | ---: | --: |
                | m 4B Q4 | 2.5 GiB | CPU | pp512 | 0.7 ± 0.0 |
                | m 4B Q4 | 2.5 GiB | CPU | tg128 | 0.6 ± 0.1 |
                """;
        assertNull(parser.parse(text).datasets().get(0).build());
    }
}
