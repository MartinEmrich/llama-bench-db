package llamabendb.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets the compute configuration of a single run: which devices were
 * actually used, expressed as an ordered list of device names such as
 * "Vulkan0,Vulkan1,CPU" or "OPENVINO0_NPU". Device names double as the keys of
 * the per-computer-version hardware map. OpenVINO devices carry a
 * "_CPU/_GPU/_NPU" suffix because the same backend can target any of them
 * (the run prints e.g. "OpenVINO: using device NPU").
 */
public final class Compute {

    private static final Set<String> KNOWN_FRAMEWORKS =
            Set.of("CPU", "Vulkan", "CUDA", "ROCm", "OpenVINO", "SYCL", "Metal");
    // Framework part is letters only so the trailing index is not swallowed.
    private static final Pattern DEVICE_TOKEN = Pattern.compile("([A-Za-z]+)(\\d*)");

    private Compute() {
    }

    /** Canonical casing for known backend frameworks; unknown names are kept as-is. */
    public static String canonicalFramework(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        if (s.isEmpty()) {
            return null;
        }
        return switch (s.toLowerCase()) {
            case "cpu" -> "CPU";
            case "vulkan" -> "Vulkan";
            case "cuda" -> "CUDA";
            case "rocm" -> "ROCm";
            case "openvino" -> "OpenVINO";
            case "sycl" -> "SYCL";
            case "metal" -> "Metal";
            default -> s;
        };
    }

    /** Device name for a framework and index, e.g. "Vulkan0", "CUDA12", "OPENVINO0_NPU". */
    public static String deviceName(String framework, Integer index, String openvinoType) {
        String f = canonicalFramework(framework);
        if (f == null) {
            return null;
        }
        if ("CPU".equals(f)) {
            return "CPU";
        }
        if ("OpenVINO".equals(f)) {
            String name = "OPENVINO" + (index != null ? index : "");
            if (openvinoType != null && !openvinoType.isBlank()) {
                name += "_" + openvinoType.strip().toUpperCase();
            }
            return name;
        }
        return index != null ? f + index : f;
    }

    /**
     * The backend framework a device name belongs to ("Vulkan0" -> "Vulkan",
     * "OPENVINO0_NPU" -> "OpenVINO"); null when it cannot be parsed.
     */
    public static String familyOf(String deviceKey) {
        if (deviceKey == null) {
            return null;
        }
        String s = deviceKey.strip();
        int us = s.lastIndexOf('_');
        if (us > 0) {
            s = s.substring(0, us);
        }
        Matcher m = DEVICE_TOKEN.matcher(s);
        if (!m.matches()) {
            return null;
        }
        return canonicalFramework(m.group(1));
    }

    /**
     * Resolves the compute string for a run from its table fields. Returns null
     * when the used devices cannot be determined conclusively (the importer
     * then warns and stores "unknown" on acknowledgement).
     */
    public static String resolve(Map<String, String> fields, String openvinoType) {
        String dev = blankToNull(fields.get("dev"));
        if (dev != null) {
            if (dev.equalsIgnoreCase("none")) {
                return "CPU";
            }
            List<String> used = new ArrayList<>();
            boolean cpu = false;
            for (String token : dev.split(",")) {
                String t = token.strip();
                if (t.isEmpty() || t.equalsIgnoreCase("none")) {
                    continue;
                }
                Matcher m = DEVICE_TOKEN.matcher(t);
                if (!m.matches() || !KNOWN_FRAMEWORKS.contains(canonicalFramework(m.group(1)))) {
                    return null;
                }
                Integer index = m.group(2).isEmpty() ? null : Integer.valueOf(m.group(2));
                String name = deviceName(m.group(1), index, openvinoType);
                if ("CPU".equals(name)) {
                    cpu = true;
                } else {
                    used.add(name);
                }
            }
            if (used.isEmpty()) {
                return "CPU";
            }
            if (moeOffload(fields)) {
                cpu = true;
            }
            return join(used, cpu);
        }

        String backend = blankToNull(fields.get("backend"));
        if (backend == null) {
            return null;
        }
        List<String> accel = new ArrayList<>();
        for (String part : backend.split(",")) {
            String f = canonicalFramework(part);
            if (f != null && !"CPU".equals(f)) {
                accel.add(f);
            }
        }
        if (accel.isEmpty()) {
            return "CPU";
        }
        int ngl = nglOf(fields);
        if (ngl == 0) {
            return "CPU";
        }
        if (accel.size() > 1) {
            // several accelerated backends available and no dev column: ambiguous
            return null;
        }
        List<String> used = new ArrayList<>();
        for (int i = 0; i < tsWeightCount(fields.get("ts")); i++) {
            used.add(deviceName(accel.get(0), i, openvinoType));
        }
        boolean cpu = (ngl > 0 && ngl < 99) || moeOffload(fields);
        return join(used, cpu);
    }

    private static String join(List<String> used, boolean cpu) {
        List<String> out = new ArrayList<>(used);
        if (cpu && !out.contains("CPU")) {
            out.add("CPU");
        }
        return String.join(",", out);
    }

    private static int nglOf(Map<String, String> fields) {
        String v = blankToNull(fields.get("ngl"));
        if (v == null) {
            return -1; // importer default: all layers on the GPU
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean moeOffload(Map<String, String> fields) {
        String v = blankToNull(fields.get("n_cpu_moe"));
        if (v == null) {
            return false;
        }
        try {
            return Integer.parseInt(v) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int tsWeightCount(String ts) {
        if (ts == null || ts.isBlank()) {
            return 1;
        }
        int count = 0;
        for (String part : ts.split("[/;]")) {
            if (!part.strip().isEmpty()) {
                count++;
            }
        }
        return Math.max(count, 1);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
