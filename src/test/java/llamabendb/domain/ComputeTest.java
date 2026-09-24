package llamabendb.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComputeTest {

    private static Map<String, String> fields(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void canonicalFrameworkNormalizesKnownNames() {
        assertEquals("CPU", Compute.canonicalFramework("cpu"));
        assertEquals("Vulkan", Compute.canonicalFramework("vulkan"));
        assertEquals("CUDA", Compute.canonicalFramework("cuda"));
        assertEquals("ROCm", Compute.canonicalFramework("rocm"));
        assertEquals("OpenVINO", Compute.canonicalFramework("OPENVINO"));
        assertEquals("SYCL", Compute.canonicalFramework("sycl"));
        assertEquals("Metal", Compute.canonicalFramework("metal"));
        // unknown names are kept as-is (best effort)
        assertEquals("hip", Compute.canonicalFramework("hip"));
        assertNull(Compute.canonicalFramework(null));
        assertNull(Compute.canonicalFramework("  "));
    }

    @Test
    void deviceNameBuildsFrameworkIndexNames() {
        assertEquals("Vulkan0", Compute.deviceName("vulkan", 0, null));
        assertEquals("CUDA12", Compute.deviceName("cuda", 12, null));
        assertEquals("CPU", Compute.deviceName("cpu", null, null));
        assertEquals("CPU", Compute.deviceName("cpu", 3, null));
    }

    @Test
    void openvinoDeviceNamesCarryTheTypeSuffix() {
        assertEquals("OPENVINO0_NPU", Compute.deviceName("openvino", 0, "NPU"));
        assertEquals("OPENVINO1_GPU", Compute.deviceName("OpenVINO", 1, "gpu"));
        // no type line seen: fall back to the plain name
        assertEquals("OPENVINO0", Compute.deviceName("openvino", 0, null));
        assertEquals("OPENVINO0", Compute.deviceName("openvino", 0, "  "));
    }

    @Test
    void familyOfParsesDeviceKeysBackToFrameworks() {
        assertEquals("Vulkan", Compute.familyOf("Vulkan0"));
        assertEquals("CUDA", Compute.familyOf("CUDA12"));
        assertEquals("OpenVINO", Compute.familyOf("OPENVINO0_NPU"));
        assertEquals("CPU", Compute.familyOf("CPU"));
        assertNull(Compute.familyOf(null));
    }

    @Test
    void explicitDevColumnWins() {
        assertEquals("Vulkan0", Compute.resolve(fields("dev", "Vulkan0"), null));
        assertEquals("Vulkan0", Compute.resolve(fields("dev", "vulkan0"), null)); // casing normalized
        assertEquals("CPU", Compute.resolve(fields("dev", "none"), null));
        assertEquals("CPU", Compute.resolve(fields("dev", "NONE"), null));
        assertEquals("CPU", Compute.resolve(fields("dev", "CPU"), null));
    }

    @Test
    void mixedDevTokensKeepTheirOrder() {
        assertEquals("CUDA0,Vulkan1", Compute.resolve(fields("dev", "CUDA0,Vulkan1"), null));
        // MoE offload means the CPU was involved as well
        assertEquals("Vulkan0,CPU", Compute.resolve(fields("dev", "Vulkan0", "n_cpu_moe", "26"), null));
    }

    @Test
    void openvinoDevTokenGetsTheRunType() {
        assertEquals("OPENVINO0_NPU", Compute.resolve(fields("dev", "OPENVINO0"), "NPU"));
        assertEquals("OPENVINO0_GPU,OPENVINO1_GPU", Compute.resolve(fields("dev", "OPENVINO0,OPENVINO1"), "GPU"));
    }

    @Test
    void unparseableDevTokenIsInconclusive() {
        assertNull(Compute.resolve(fields("dev", "auto"), null));
        assertNull(Compute.resolve(fields("dev", "Vulkan0,?weird"), null));
        // unknown framework: better to flag than to store a guess
        assertNull(Compute.resolve(fields("dev", "WeirdBackend0"), null));
    }

    @Test
    void withoutDevColumnCpuOnlyBackendsResolveToCpu() {
        assertEquals("CPU", Compute.resolve(fields("backend", "CPU"), null));
        // ngl=0: nothing offloaded even if a GPU backend is available
        assertEquals("CPU", Compute.resolve(fields("backend", "Vulkan", "ngl", "0"), null));
    }

    @Test
    void withoutDevColumnSingleBackendIsInferred() {
        assertEquals("Vulkan0", Compute.resolve(fields("backend", "Vulkan"), null));
        // tensor split weights name as many devices as there are weights
        assertEquals("Vulkan0,Vulkan1", Compute.resolve(fields("backend", "Vulkan", "ts", "36.00/13.00"), null));
        // partial offload leaves layers on the CPU
        assertEquals("Vulkan0,Vulkan1,CPU",
                Compute.resolve(fields("backend", "Vulkan", "ngl", "30", "ts", "30.00/20.00"), null));
        // all layers offloaded (ngl 99 counts as -1) but MoE experts on the CPU
        assertEquals("Vulkan0,Vulkan1,CPU",
                Compute.resolve(fields("backend", "Vulkan", "ngl", "99", "ts", "36/13", "n_cpu_moe", "26"), null));
        // all layers offloaded, no MoE offload: GPU only
        assertEquals("Vulkan0,Vulkan1",
                Compute.resolve(fields("backend", "Vulkan", "ngl", "-1", "ts", "36/13"), null));
    }

    @Test
    void withoutDevColumnOpenvinoUsesTheRunType() {
        assertEquals("OPENVINO0_GPU,OPENVINO1_GPU",
                Compute.resolve(fields("backend", "OpenVINO", "ts", "50/50"), "GPU"));
        assertEquals("OPENVINO0",
                Compute.resolve(fields("backend", "OpenVINO"), null));
    }

    @Test
    void ambiguousOrMissingDataIsInconclusive() {
        assertNull(Compute.resolve(fields(), null)); // no backend at all
        assertNull(Compute.resolve(fields("backend", ""), null));
        // several accelerated backends available, no dev column: which one ran?
        assertNull(Compute.resolve(fields("backend", "Vulkan,OPENVINO"), null));
        assertNull(Compute.resolve(fields("backend", "Vulkan,OPENVINO", "ts", "50/50"), null));
    }
}
