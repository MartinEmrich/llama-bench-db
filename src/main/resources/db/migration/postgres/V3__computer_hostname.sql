ALTER TABLE computer ADD COLUMN hostname VARCHAR(128);
UPDATE computer SET hostname = name;
CREATE INDEX idx_computer_hostname ON computer (hostname);
