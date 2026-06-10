CREATE TABLE IF NOT EXISTS mn_check_state (
  id SMALLINT PRIMARY KEY CHECK (id = 1),
  payload JSONB NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS mn_check_state_history (
  id BIGSERIAL PRIMARY KEY,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mn_check_state_history_created_at
  ON mn_check_state_history (created_at DESC);

CREATE TABLE IF NOT EXISTS mn_check_files (
  name TEXT PRIMARY KEY,
  content_type TEXT NOT NULL,
  content BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
