CREATE TABLE inventarios (
  id BIGSERIAL PRIMARY KEY,
  filial_id BIGINT NOT NULL REFERENCES filiais(id),
  importacao_saldo_id BIGINT NOT NULL REFERENCES importacoes_saldo(id),
  nome TEXT NOT NULL CHECK (btrim(nome) <> ''),
  tipo VARCHAR(16) NOT NULL CHECK (tipo IN ('GERAL', 'PARCIAL', 'AUDITORIA', 'CICLICA')),
  modo VARCHAR(16) NOT NULL CHECK (modo IN ('NORMAL', 'CEGO')),
  status VARCHAR(24) NOT NULL DEFAULT 'RASCUNHO' CHECK (status IN (
    'RASCUNHO', 'ABERTO', 'EM_CONTAGEM', 'EM_RECONTAGEM',
    'EM_INVESTIGACAO', 'FINALIZADO', 'CANCELADO'
  )),
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  criado_por TEXT NOT NULL,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  aberto_por TEXT,
  aberto_em TIMESTAMPTZ,
  encerrado_por TEXT,
  encerrado_em TIMESTAMPTZ,
  cancelado_por TEXT,
  cancelado_em TIMESTAMPTZ
);

CREATE TABLE inventario_itens (
  id BIGSERIAL PRIMARY KEY,
  inventario_id BIGINT NOT NULL REFERENCES inventarios(id) ON DELETE CASCADE,
  sku VARCHAR(64) NOT NULL,
  descricao_snapshot TEXT NOT NULL DEFAULT '',
  saldo_snapshot INTEGER NOT NULL CHECK (saldo_snapshot >= 0),
  estado VARCHAR(24) NOT NULL DEFAULT 'PENDENTE' CHECK (
    estado IN ('PENDENTE', 'EM_CONTAGEM', 'CONTADO')
  ),
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (inventario_id, sku)
);

CREATE INDEX idx_inventarios_filial_status
  ON inventarios(filial_id, status, criado_em DESC, id DESC);
CREATE INDEX idx_inventarios_importacao
  ON inventarios(importacao_saldo_id);
CREATE INDEX idx_inventario_itens_inventario
  ON inventario_itens(inventario_id, sku);
