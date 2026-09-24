ALTER TABLE result ADD COLUMN compute VARCHAR(255);

-- Backfill from the raw columns; deliberately simpler than the import-time
-- inference. Conclusive cases get their obvious value, the rest get 'unknown'
-- (fixable later via the edit-result feature).
UPDATE result SET compute = CASE
    WHEN devices IS NOT NULL AND TRIM(devices) <> '' AND UPPER(TRIM(devices)) <> 'NONE' THEN devices
    WHEN devices IS NOT NULL AND UPPER(TRIM(devices)) = 'NONE' THEN 'CPU'
    WHEN (devices IS NULL OR TRIM(devices) = '') AND backend IS NOT NULL AND UPPER(TRIM(backend)) = 'VULKAN' THEN 'Vulkan0'
    WHEN (devices IS NULL OR TRIM(devices) = '') AND backend IS NOT NULL AND UPPER(TRIM(backend)) = 'CPU' THEN 'CPU'
    ELSE 'unknown'
END;

-- Nullable for parity with the other dialects (see h2); Java treats null as an empty map.
ALTER TABLE computer_version ADD COLUMN devices JSONB;
