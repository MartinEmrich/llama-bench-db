package llamabendb.importer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportParserTest {

    private final ImportParser parser = new ImportParser();

    private String sample(String name) throws IOException {
        return Files.readString(Path.of("samples", name));
    }

    @Test
    void surfacegoFullFileIsRejectedAsMultiModel() throws IOException {
        ImportException e = assertThrows(ImportException.class, () -> parser.parse(sample("surfacego.txt")));
        assertTrue(e.getMessage().contains("multiple models"));
        assertTrue(e.getMessage().contains("qwen35 4B Q4_K - Medium"));
    }

    @Test
    void surfacegoSectionsParseToSingleDatasets() throws IOException {
        String[] sections = sample("surfacego.txt").split("(?m)^---\\s*$");
        ImportParser.ParseResult r = parser.parse(sections[0]);
        assertEquals(1, r.datasets().size());
        ImportParser.Dataset d = r.datasets().get(0);
        assertEquals("qwen35 4B Q4_K - Medium", d.modelString());
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
    void auroraSkipsEmptyTablesAndCapturesUnknownColumns() throws IOException {
        ImportParser.ParseResult r = parser.parse(sample("aurora-qwen3.8-flash-next.txt"));
        assertEquals(3, r.datasets().size());
        assertEquals(1, r.modelStrings().size());
        assertEquals("qwen4exp A3B IQ3_XXS - 3.0625 bpw", r.modelStrings().get(0));

        long withNcmoe = r.datasets().stream().filter(d -> d.fields().containsKey("n_cpu_moe")).count();
        assertEquals(2, withNcmoe);

        ImportParser.Dataset first = r.datasets().get(0);
        assertEquals("off", first.fields().get("lazy_mode"));
        assertFalse(first.fields().containsKey("n_cpu_moe"));
        assertEquals("30.00/20.00", first.fields().get("ts"));
        assertEquals("30", first.fields().get("ngl"));
    }

    @Test
    void bonsaiFullFileIsMultiModel() throws IOException {
        ImportException e = assertThrows(ImportException.class, () -> parser.parse(sample("bonsai-gwaihir.txt")));
        assertTrue(e.getMessage().contains("multiple models"));
    }

    @Test
    void qwen359bHasTwoDatasetsInOnePaste() throws IOException {
        ImportParser.ParseResult r = parser.parse(sample("qwen3.5-9B-gwaihir.txt"));
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
