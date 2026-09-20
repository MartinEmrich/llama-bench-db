package llamabendb.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a lexicographically sortable key for quantization strings, ordering by
 * bit count first, then t-shirt size class, then family, e.g. IQ3_XXS &lt; Q4_0 &lt; Q5_K_M.
 */
public final class QuantSortKey {

    private static final Pattern QUANT = Pattern.compile("^([A-Z]*)(\\d+(?:\\.\\d+)?)_(.+)$");
    private static final Pattern SIZE_TOKEN = Pattern.compile("(XXS|XS|S|M|L)$");

    private QuantSortKey() {
    }

    public static String of(String quantization) {
        if (quantization == null || quantization.isBlank()) {
            return "99|00|?|" + (quantization == null ? "" : quantization);
        }
        Matcher m = QUANT.matcher(quantization.trim());
        if (!m.matches()) {
            return "99|00|?|" + quantization;
        }
        int bits;
        try {
            bits = Integer.parseInt(m.group(2).split("\\.")[0]);
        } catch (NumberFormatException e) {
            return "99|00|?|" + quantization;
        }
        String suffix = m.group(3);
        int sizeRank = 0;
        Matcher s = SIZE_TOKEN.matcher(suffix);
        if (s.find()) {
            sizeRank = switch (s.group(1)) {
                case "XXS" -> 1;
                case "XS" -> 2;
                case "S" -> 3;
                case "M" -> 4;
                default -> 5; // L
            };
        }
        return String.format("%02d|%02d|%s|%s", bits, sizeRank, m.group(1), quantization.trim());
    }
}
