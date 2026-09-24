# TODO — llama-bench-db implementation plan

This file tracks open work only; completed tasks should be weeded out.

## Locked decisions

- **Stack:** Spring Boot 4.1.1 (Hibernate ORM 7.4) · Java 25 (Gradle toolchain) · Gradle 9.7.1 wrapper at repo root, single module · JSON column via `@JdbcTypeCode(SqlTypes.JSON)` on a `Map<String,Object>` (Hibernate 7 removed `JsonType`) · Spring Data JPA · Flyway with per-dialect locations · H2 file DB local / MariaDB / PostgreSQL — same app, no code changes · Nuxt SPA in `frontend/` · JUnit Jupiter via starter-test
- **Schema strategy:** typed columns for known/stable fields; unrecognized llama-bench parameters go into a JSON map column on Result. When an attribute earns filter/sort status it gets **promoted**: Flyway migration adds the column + backfills it from the JSON, importer writes the real column (blob cleanup skipped)
- **Import:** server-side parsing, no auth. The import form autodetects per run from the pasted console output — computer via hostname in the shell prompt (matched against `computer.hostname`, non-unique; on collision the newest version wins), model via the `-hf`/`-hfr`/`--hf-repo` parameter (unknown models created silently). Explicit dropdown selection overrides autodetection; an explicit model still enforces the strict single-model paste rule, while autodetect mode links each run of a multi-model paste to its own model
- **Devices:** `result.devices` is the single record of which devices a run used — the resolved list (e.g. `Vulkan0,CPU`), null when undeterminable (import warns `DEVICES_UNKNOWN`, leaves it empty on acknowledgement; fixable via the edit endpoint). The raw llama-bench `dev` column value is preserved in the params JSON under `"dev"` as a record of what the run printed. The former `compute` column was consolidated into devices by the V5 Java migration (Flyway Java migrations are registered as Spring beans, not per-dialect SQL files; on H2, JSON columns must be written via `setBytes` like Hibernate's `H2JsonJdbcType` — VARCHAR values get stored as JSON *strings*)
- **Layout:** Gradle project at repo root; Nuxt in `frontend/`

## Open items

- [ ] MariaDB smoke test — scrapped for now (if revisited: same pattern as the PostgreSQL one, the module is `org.flywaydb:flyway-mysql`)
- [ ] Treat `ngl >= 99` as `-1` (all layers) in queries/evaluation — store the raw value as-is, but when filtering/sorting/comparing results treat any ngl of 99 or above the same as -1
- [ ] Later release — clean up the scrapped compute column from migration history. Flyway CE cannot remove applied migrations, and V4 is dual-purpose (adds `result.compute` *and* `computer_version.devices`, which stays), so: (1) add a Java migration that idempotently ensures `computer_version.devices` exists (metadata check — the three DBs don't uniformly support `ADD COLUMN IF NOT EXISTS`), (2) delete the V4+V5 files, (3) set `spring.flyway.ignore-missing-migrations=true`. Production: no-op + ignored history entries; fresh installs: V1–V3 + new migration = complete schema. Alternative: skip it — V4/V5 stay as harmless no-ops on fresh installs
- [ ] Intelligently handle fuzzy differences in hardware check, e.g. this should still pass: `HARDWARE_MISMATCH: run on 'aurora' reports hardware that differs from the stored version of 2026-09-23: Vulkan0: stored 'Radeon RX 7900 XTX 24GB' but run shows 'AMD Radeon RX 7900 XTX (RADV NAVI31) (radv)'; Vulkan1: stored 'Radeon RX 7800 XT' but run shows 'AMD Radeon RX 7800 XT (RADV NAVI32) (radv)' - review or update the computer's versions
`.

## Phase 8 — Later: parameter normalization (planned, not started)

Background: llama-bench parameters appear in three spellings — CLI short form (`-ncmoe`), CLI long form (`--n-cpu-moe`) and a table-header variant (`n_cpu_moe`). The full parameter list is captured in `llama-bench-help.txt` (repo root). Unknown parameters currently pass through into the `params` JSON as-is, which is the intended fallback.

- [ ] Canonical parameter map: explicitly enumerate all known llama-bench parameters from `llama-bench-help.txt` — short form / long form / table-header variant → one canonical name; unknown parameters keep the as-is fallback
- [ ] Special semantics for the n_cpu_moe family: `-ncmoe/--n-cpu-moe <n>` (exact expert count) vs `cmoe`/`cpu-moe` ("all") — treat as one logical attribute with distinct value semantics; decide normalization/storage when implemented
