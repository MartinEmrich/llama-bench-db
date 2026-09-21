package llamabendb.importer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses raw llama-bench console output (which may contain command lines, device
 * dumps, download progress, errors and aborted runs) into result datasets.
 * Parsing is header-name driven, never positional. Blank lines inside a table
 * are tolerated; a new table starts at every "model" header row.
 */
public final class ImportParser {

    public record Dataset(
            String modelString,
            Double sizeGiB,
            Map<String, String> fields,
            int ppTokens,
            int tgTokens,
            double ppTps,
            double tgTps,
            double ppDeviation,
            double tgDeviation,
            String build,
            String hostname,
            String hfModelId
    ) {
    }

    public record ParseResult(List<Dataset> datasets, List<String> modelStrings, int emptyTablesSkipped) {
    }

    private record Table(int line, int lastRowIdx, List<Map<String, String>> rows) {
    }

    /** Hostname and -hf model id taken from the command line preceding a table. */
    public record CommandLineInfo(String hostname, String hfModelId) {
    }

    private record CommandLine(int idx, String hostname, String hfModelId) {
    }

    private static final Pattern TPS = Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*±\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$");
    private static final Pattern PP_TEST = Pattern.compile("^pp(\\d+)$");
    private static final Pattern TG_TEST = Pattern.compile("^tg(\\d+)$");
    private static final Pattern SIZE = Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(GiB|MiB)\\s*$");
    private static final Pattern BUILD_LINE = Pattern.compile("^\\s*build:\\s*(\\S.*?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001b\\[[0-9;]*[A-Za-z]");
    private static final Pattern HOSTNAME = Pattern.compile("@([A-Za-z0-9][A-Za-z0-9._-]*)");
    private static final List<String> HF_FLAGS = List.of("-hf", "-hfr", "--hf-repo");

    public ParseResult parse(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<Table> tables = extractTables(lines);
        List<CommandLine> commands = findCommandLines(lines);
        List<Dataset> datasets = new ArrayList<>();
        int emptySkipped = 0;
        for (int s = 0; s < tables.size(); s++) {
            Table table = tables.get(s);
            if (table.rows().isEmpty()) {
                emptySkipped++;
                continue;
            }
            datasets.addAll(toDatasets(table, findBuild(lines, tables, s), attributedCommand(commands, table)));
        }
        if (datasets.isEmpty()) {
            throw new ImportException("no result data found in the pasted text");
        }
        // A multi-model paste is only rejected when a single model was selected explicitly;
        // in autodetect mode each run is linked to the model of its own command line.
        List<String> models = datasets.stream()
                .map(Dataset::modelString)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return new ParseResult(datasets, models, emptySkipped);
    }

    /**
     * Shell command lines (prompt + llama-bench invocation) found in the text, in order.
     * A command line is any non-table line mentioning "llama-bench"; ANSI escapes are
     * stripped first. The hostname comes from the prompt part, the model id from the
     * -hf/-hfr/--hf-repo parameter (null when absent).
     */
    public List<CommandLineInfo> detectCommandLines(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        return findCommandLines(lines).stream()
                .map(c -> new CommandLineInfo(c.hostname(), c.hfModelId()))
                .toList();
    }

    private List<CommandLine> findCommandLines(String[] lines) {
        List<CommandLine> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = ANSI_ESCAPE.matcher(lines[i]).replaceAll("");
            if (line.isBlank() || line.stripLeading().startsWith("|")) {
                continue;
            }
            int cmdIdx = line.lastIndexOf("llama-bench");
            if (cmdIdx < 0) {
                continue;
            }
            out.add(new CommandLine(i, promptHostname(line.substring(0, cmdIdx)), hfModelId(line)));
        }
        return out;
    }

    /** The last command line before the table header is attributed to that table. */
    private CommandLineInfo attributedCommand(List<CommandLine> commands, Table table) {
        int headerIdx = table.line() - 1;
        CommandLineInfo info = null;
        for (CommandLine c : commands) {
            if (c.idx() >= headerIdx) {
                break;
            }
            info = new CommandLineInfo(c.hostname(), c.hfModelId());
        }
        return info;
    }

    private String promptHostname(String prompt) {
        Matcher m = HOSTNAME.matcher(prompt);
        String host = null;
        while (m.find()) {
            host = m.group(1);
        }
        return host;
    }

    private String hfModelId(String line) {
        String[] tokens = line.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            for (String flag : HF_FLAGS) {
                if (t.equals(flag)) {
                    return i + 1 < tokens.length ? tokens[i + 1] : null;
                }
                if (t.startsWith(flag + "=")) {
                    return t.substring(flag.length() + 1);
                }
            }
        }
        return null;
    }

    /**
     * The llama-bench build line (e.g. "build: 861bd3c10 (11029)") printed after a
     * table block is attributed to that table; the last match in the gap before the
     * next table wins. Build lines before any table are ignored.
     */
    private String findBuild(String[] lines, List<Table> tables, int index) {
        Table table = tables.get(index);
        int gapEnd = index + 1 < tables.size() ? tables.get(index + 1).line() - 1 : lines.length;
        String build = null;
        for (int i = table.lastRowIdx() + 1; i < gapEnd; i++) {
            Matcher m = BUILD_LINE.matcher(lines[i]);
            if (m.matches()) {
                build = m.group(1);
            }
        }
        return build;
    }

    private List<Table> extractTables(String[] lines) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 1 < lines.length; i++) {
            if (isHeaderLine(lines[i]) && isSeparatorLine(lines[i + 1])) {
                starts.add(i);
            }
        }
        List<Table> tables = new ArrayList<>();
        for (int s = 0; s < starts.size(); s++) {
            int headerIdx = starts.get(s);
            int end = s + 1 < starts.size() ? starts.get(s + 1) : lines.length;
            List<String> header = splitRow(lines[headerIdx]);
            List<Map<String, String>> rows = new ArrayList<>();
            int lastRowIdx = headerIdx + 1;
            for (int i = headerIdx + 2; i < end; i++) {
                String line = lines[i];
                if (line.isBlank()) {
                    continue;
                }
                if (!line.stripLeading().startsWith("|")) {
                    break;
                }
                List<String> cells = splitRow(line);
                if (cells.size() != header.size()) {
                    throw new ImportException("malformed table at line " + (headerIdx + 1)
                            + ": row at line " + (i + 1) + " has " + cells.size()
                            + " columns, expected " + header.size());
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++) {
                    row.put(header.get(c), cells.get(c));
                }
                rows.add(row);
                lastRowIdx = i;
            }
            tables.add(new Table(headerIdx + 1, lastRowIdx, rows));
        }
        return tables;
    }

    private boolean isHeaderLine(String line) {
        if (!line.stripLeading().startsWith("|")) {
            return false;
        }
        List<String> cells = splitRow(line);
        return !cells.isEmpty() && "model".equals(cells.get(0));
    }

    private boolean isSeparatorLine(String line) {
        if (!line.stripLeading().startsWith("|")) {
            return false;
        }
        for (String cell : splitRow(line)) {
            if (cell.isEmpty() || !cell.matches("[-: ]*")) {
                return false;
            }
        }
        return true;
    }

    private List<String> splitRow(String line) {
        String s = line.strip();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        return Arrays.stream(s.split("\\|", -1)).map(String::strip).toList();
    }

    private List<Dataset> toDatasets(Table table, String build, CommandLineInfo command) {
        boolean hasTestColumn = table.rows().get(0).containsKey("test");
        if (!hasTestColumn) {
            throw new ImportException("table at line " + table.line() + " has no 'test' column");
        }

        Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
        for (Map<String, String> row : table.rows()) {
            String key = row.entrySet().stream()
                    .filter(e -> !e.getKey().equals("test") && !e.getKey().equals("t/s"))
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("\u0001"));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<Dataset> datasets = new ArrayList<>();
        for (List<Map<String, String>> group : groups.values()) {
            Map<String, String> ppRow = null;
            Map<String, String> tgRow = null;
            int ppTokens = -1;
            int tgTokens = -1;
            for (Map<String, String> row : group) {
                String test = row.getOrDefault("test", "");
                Matcher pp = PP_TEST.matcher(test);
                Matcher tg = TG_TEST.matcher(test);
                if (pp.matches()) {
                    if (ppRow != null) {
                        throw new ImportException("duplicate pp row in dataset at table line " + table.line());
                    }
                    ppRow = row;
                    ppTokens = Integer.parseInt(pp.group(1));
                } else if (tg.matches()) {
                    if (tgRow != null) {
                        throw new ImportException("duplicate tg row in dataset at table line " + table.line());
                    }
                    tgRow = row;
                    tgTokens = Integer.parseInt(tg.group(1));
                } else {
                    throw new ImportException("unrecognized test '" + test + "' in table at line " + table.line());
                }
            }
            if (ppRow == null || tgRow == null) {
                throw new ImportException("incomplete dataset in table at line " + table.line()
                        + ": expected one pp and one tg row, found pp=" + (ppRow != null) + ", tg=" + (tgRow != null));
            }

            double[] pp = parseTps(ppRow.get("t/s"), "pp");
            double[] tg = parseTps(tgRow.get("t/s"), "tg");
            Map<String, String> fields = new LinkedHashMap<>(ppRow);
            fields.remove("test");
            fields.remove("t/s");

            datasets.add(new Dataset(
                    ppRow.get("model"),
                    parseSize(ppRow.get("size")),
                    fields,
                    ppTokens, tgTokens,
                    pp[0], tg[0], pp[1], tg[1],
                    build,
                    command != null ? command.hostname() : null,
                    command != null ? command.hfModelId() : null));
        }
        return datasets;
    }

    private double[] parseTps(String value, String which) {
        if (value == null || value.isBlank()) {
            throw new ImportException("missing t/s value for " + which);
        }
        Matcher m = TPS.matcher(value);
        if (!m.matches()) {
            throw new ImportException("unparseable t/s value '" + value + "'");
        }
        return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
    }

    private Double parseSize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher m = SIZE.matcher(value);
        if (!m.matches()) {
            throw new ImportException("unparseable size '" + value + "'");
        }
        double v = Double.parseDouble(m.group(1));
        return "MiB".equals(m.group(2)) ? v / 1024.0 : v;
    }
}
