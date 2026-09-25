-- V5: Adicionar dimensão de localização, escopo de recontagem e snapshot de apuração

ALTER TABLE ocorrencias_contagem
  ADD COLUMN localizacao VARCHAR(32) NOT NULL DEFAULT 'GERAL'
  CHECK (localizacao IN ('GERAL', 'VENDAS', 'DEPOSITO', 'TROCAS', 'OUTRO'));

ALTER TABLE rodadas_contagem
  ADD COLUMN encerramento_forcado BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN pendentes_no_fechamento INTEGER NOT NULL DEFAULT 0;

CREATE TABLE rodada_itens (
  id BIGSERIAL PRIMARY KEY,
  rodada_id BIGINT NOT NULL REFERENCES rodadas_contagem(id) ON DELETE RESTRICT,
  inventario_item_id BIGINT NOT NULL REFERENCES inventario_itens(id) ON DELETE RESTRICT,
  origem_motivo VARCHAR(32) NOT NULL CHECK (origem_motivo IN ('DIVERGENCIA', 'NAO_CONTADO_R1', 'MANUAL')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (rodada_id, inventario_item_id)
);

CREATE INDEX idx_rodada_itens_item ON rodada_itens(rodada_id, inventario_item_id);

CREATE TABLE apuracoes_rodada (
  id BIGSERIAL PRIMARY KEY,
  rodada_id BIGINT NOT NULL REFERENCES rodadas_contagem(id) ON DELETE RESTRICT,
  inventario_item_id BIGINT NOT NULL REFERENCES inventario_itens(id) ON DELETE RESTRICT,
  sku VARCHAR(64) NOT NULL,
  saldo_snapshot INTEGER NOT NULL,
  contado BOOLEAN NOT NULL,
  quantidade_fisica INTEGER,
  diferenca INTEGER,
  estado VARCHAR(32) NOT NULL CHECK (estado IN (
    'CONFORME', 'DIVERGENTE', 'NAO_CONTADO',
    'CONFORME_APOS_RECONTAGEM', 'DIVERGENCIA_CONFIRMADA'
  )),
  detalhes_localizacao_condicao JSONB,
  apurado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  apurado_por TEXT NOT NULL,
  UNIQUE (rodada_id, inventario_item_id)
);

CREATE INDEX idx_apuracoes_rodada_estado ON apuracoes_rodada(rodada_id, estado);
