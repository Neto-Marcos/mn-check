CREATE TABLE IF NOT EXISTS importacoes_saldo (
  id BIGSERIAL PRIMARY KEY,
  nome_arquivo TEXT NOT NULL,
  quantidade_skus INTEGER NOT NULL CHECK (quantidade_skus >= 0),
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  paginas_processadas INTEGER NOT NULL DEFAULT 0,
  linhas_ignoradas INTEGER NOT NULL DEFAULT 0,
  skus_duplicados INTEGER NOT NULL DEFAULT 0,
  conflitos_encontrados INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS saldos (
  id BIGSERIAL PRIMARY KEY,
  sku VARCHAR(64) NOT NULL,
  saldo INTEGER NOT NULL CHECK (saldo >= 0),
  importacao_id BIGINT NOT NULL REFERENCES importacoes_saldo(id) ON DELETE CASCADE,
  UNIQUE (importacao_id, sku)
);

CREATE TABLE IF NOT EXISTS contagens (
  id BIGSERIAL PRIMARY KEY,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  operador TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS itens_contagem (
  id BIGSERIAL PRIMARY KEY,
  contagem_id BIGINT NOT NULL REFERENCES contagens(id) ON DELETE CASCADE,
  sku VARCHAR(64) NOT NULL,
  saldo_sistema INTEGER NOT NULL CHECK (saldo_sistema >= 0),
  quantidade_contada INTEGER NOT NULL CHECK (quantidade_contada >= 0),
  diferenca INTEGER NOT NULL,
  UNIQUE (contagem_id, sku)
);

CREATE INDEX IF NOT EXISTS idx_saldos_importacao
  ON saldos(importacao_id);

CREATE INDEX IF NOT EXISTS idx_contagens_criado_em
  ON contagens(criado_em DESC);

CREATE INDEX IF NOT EXISTS idx_itens_contagem_contagem
  ON itens_contagem(contagem_id);

-- Estado operacional restante e anexos também ficam no PostgreSQL.
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

CREATE TABLE IF NOT EXISTS mn_check_files (
  name TEXT PRIMARY KEY,
  content_type TEXT NOT NULL,
  content BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
