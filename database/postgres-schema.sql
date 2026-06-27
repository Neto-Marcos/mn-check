CREATE TABLE IF NOT EXISTS importacoes_saldo (
  id BIGSERIAL PRIMARY KEY,
  nome_arquivo TEXT NOT NULL,
  importado_por TEXT NOT NULL DEFAULT 'Sistema',
  quantidade_skus INTEGER NOT NULL CHECK (quantidade_skus >= 0),
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  paginas_processadas INTEGER NOT NULL DEFAULT 0,
  total_linhas_lidas INTEGER NOT NULL DEFAULT 0,
  linhas_ignoradas INTEGER NOT NULL DEFAULT 0,
  skus_duplicados INTEGER NOT NULL DEFAULT 0,
  conflitos_encontrados INTEGER NOT NULL DEFAULT 0,
  itens_alterados INTEGER NOT NULL DEFAULT 0,
  itens_removidos INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS saldos (
  id BIGSERIAL PRIMARY KEY,
  sku VARCHAR(64) NOT NULL,
  saldo INTEGER NOT NULL CHECK (saldo >= 0),
  importacao_id BIGINT NOT NULL REFERENCES importacoes_saldo(id) ON DELETE CASCADE,
  UNIQUE (importacao_id, sku)
);

CREATE TABLE IF NOT EXISTS estoque_produtos (
  sku VARCHAR(64) PRIMARY KEY,
  saldo_sistema INTEGER NOT NULL CHECK (saldo_sistema >= 0),
  saldo_contado INTEGER NOT NULL DEFAULT 0 CHECK (saldo_contado >= 0),
  ativo BOOLEAN NOT NULL DEFAULT TRUE,
  ultima_atualizacao TIMESTAMPTZ NOT NULL DEFAULT now(),
  ultima_contagem_em TIMESTAMPTZ,
  importacao_id BIGINT NOT NULL REFERENCES importacoes_saldo(id)
);

CREATE TABLE IF NOT EXISTS contagens (
  id BIGSERIAL PRIMARY KEY,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  operador TEXT NOT NULL,
  importacao_id BIGINT REFERENCES importacoes_saldo(id),
  status VARCHAR(24) NOT NULL DEFAULT 'ABERTA'
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

CREATE TABLE IF NOT EXISTS conferencias (
  id BIGSERIAL PRIMARY KEY,
  mapa_id TEXT NOT NULL UNIQUE,
  operador TEXT NOT NULL,
  status VARCHAR(24) NOT NULL CHECK (status IN ('EM_ANDAMENTO', 'PAUSADA', 'FINALIZADA', 'CANCELADA')),
  iniciado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  finalizado_em TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS itens_conferencia (
  id BIGSERIAL PRIMARY KEY,
  conferencia_id BIGINT NOT NULL REFERENCES conferencias(id) ON DELETE CASCADE,
  sku VARCHAR(64) NOT NULL,
  quantidade_esperada INTEGER NOT NULL CHECK (quantidade_esperada >= 0),
  quantidade_conferida INTEGER NOT NULL CHECK (quantidade_conferida >= 0),
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (conferencia_id, sku)
);

CREATE TABLE IF NOT EXISTS historico_scanner (
  id BIGSERIAL PRIMARY KEY,
  mapa_id TEXT NOT NULL,
  operador TEXT NOT NULL,
  codigo_esperado VARCHAR(16) NOT NULL,
  codigo_lido VARCHAR(16) NOT NULL,
  aprovado BOOLEAN NOT NULL,
  motivo TEXT NOT NULL,
  origem VARCHAR(16) NOT NULL,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

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

CREATE INDEX IF NOT EXISTS idx_saldos_importacao ON saldos(importacao_id);
CREATE INDEX IF NOT EXISTS idx_estoque_produtos_ativo ON estoque_produtos(ativo, sku);
CREATE INDEX IF NOT EXISTS idx_contagens_criado_em ON contagens(criado_em DESC);
CREATE INDEX IF NOT EXISTS idx_itens_contagem_contagem ON itens_contagem(contagem_id);
CREATE INDEX IF NOT EXISTS idx_conferencias_status ON conferencias(status, atualizado_em DESC);
CREATE INDEX IF NOT EXISTS idx_historico_scanner_mapa ON historico_scanner(mapa_id, criado_em DESC);
