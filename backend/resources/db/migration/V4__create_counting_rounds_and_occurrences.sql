CREATE TABLE rodadas_contagem (
  id BIGSERIAL PRIMARY KEY,
  inventario_id BIGINT NOT NULL REFERENCES inventarios(id) ON DELETE RESTRICT,
  numero INTEGER NOT NULL CHECK (numero >= 1),
  tipo VARCHAR(24) NOT NULL DEFAULT 'CONTAGEM' CHECK (tipo IN ('CONTAGEM', 'RECONTAGEM', 'INVESTIGACAO')),
  status VARCHAR(24) NOT NULL DEFAULT 'EM_ANDAMENTO' CHECK (status IN ('EM_ANDAMENTO', 'FINALIZADA', 'CANCELADA')),
  iniciada_por TEXT NOT NULL,
  iniciada_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  finalizada_por TEXT,
  finalizada_em TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (inventario_id, numero)
);

CREATE TABLE ocorrencias_contagem (
  id BIGSERIAL PRIMARY KEY,
  rodada_id BIGINT NOT NULL REFERENCES rodadas_contagem(id) ON DELETE RESTRICT,
  inventario_item_id BIGINT NOT NULL REFERENCES inventario_itens(id) ON DELETE RESTRICT,
  sku VARCHAR(64) NOT NULL,
  quantidade INTEGER NOT NULL CHECK (quantidade >= 0),
  categoria VARCHAR(32) NOT NULL DEFAULT 'BOA' CHECK (categoria IN ('BOA', 'AVARIA', 'ASSISTENCIA', 'OUTROS')),
  tipo_acao VARCHAR(16) NOT NULL DEFAULT 'DEFINIR' CHECK (tipo_acao IN ('DEFINIR', 'SOMAR', 'CORRECAO')),
  operador TEXT NOT NULL,
  client_event_id UUID NOT NULL,
  origem VARCHAR(32) NOT NULL DEFAULT 'SCANNER' CHECK (origem IN ('SCANNER', 'MANUAL', 'OFFLINE')),
  dispositivo TEXT,
  client_timestamp TIMESTAMPTZ,
  server_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
  referencia_id BIGINT REFERENCES ocorrencias_contagem(id) ON DELETE RESTRICT,
  UNIQUE (client_event_id)
);

CREATE INDEX idx_rodadas_contagem_inventario ON rodadas_contagem(inventario_id, status, numero);
CREATE INDEX idx_ocorrencias_rodada_item ON ocorrencias_contagem(rodada_id, inventario_item_id);
CREATE INDEX idx_ocorrencias_rodada_sku ON ocorrencias_contagem(rodada_id, sku);
CREATE INDEX idx_ocorrencias_server_timestamp ON ocorrencias_contagem(server_timestamp DESC);
