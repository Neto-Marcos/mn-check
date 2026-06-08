CREATE TABLE users (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('admin', 'separation', 'expedition', 'stock')),
  label TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE maps (
  id TEXT PRIMARY KEY,
  route TEXT NOT NULL,
  client TEXT NOT NULL,
  carrier TEXT NOT NULL,
  branch TEXT NOT NULL,
  map_date TEXT NOT NULL,
  status TEXT NOT NULL,
  created_by TEXT,
  attachment_name TEXT,
  attachment_type TEXT,
  attachment_path TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE map_items (
  id BIGSERIAL PRIMARY KEY,
  map_id TEXT NOT NULL REFERENCES maps(id) ON DELETE CASCADE,
  sku TEXT NOT NULL,
  name TEXT NOT NULL,
  quantity INTEGER NOT NULL,
  ok BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE count_items (
  sku TEXT PRIMARY KEY,
  system_quantity INTEGER NOT NULL,
  counted_quantity INTEGER NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE error_records (
  id BIGSERIAL PRIMARY KEY,
  map_id TEXT NOT NULL,
  issue TEXT NOT NULL,
  owner TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
  id BIGSERIAL PRIMARY KEY,
  at TIMESTAMPTZ NOT NULL DEFAULT now(),
  user_name TEXT NOT NULL,
  action TEXT NOT NULL,
  description TEXT NOT NULL
);
