-- V7: Fechamento formal, resultado consolidado, snapshot por item e eventos de inventário

-- 1. Atualizar constraint de status em inventarios para incluir ENCERRADO
ALTER TABLE inventarios DROP CONSTRAINT IF EXISTS inventarios_status_check;
ALTER TABLE inventarios ADD CONSTRAINT inventarios_status_check CHECK (status IN (
  'RASCUNHO', 'ABERTO', 'EM_CONTAGEM', 'EM_RECONTAGEM',
  'EM_INVESTIGACAO', 'FINALIZADO', 'ENCERRADO', 'CANCELADO'
));

-- Metadados de fechamento na sessão de inventário
ALTER TABLE inventarios
  ADD COLUMN IF NOT EXISTS tipo_fechamento VARCHAR(24) CHECK (tipo_fechamento IN ('NORMAL', 'EXCEPCIONAL')),
  ADD COLUMN IF NOT EXISTS justificativa_fechamento TEXT;

-- 2. Tabela de resultado consolidado da sessão de inventário
CREATE TABLE resultados_inventario (
  id BIGSERIAL PRIMARY KEY,
  inventario_id BIGINT NOT NULL REFERENCES inventarios(id) ON DELETE RESTRICT,
  filial_id BIGINT NOT NULL REFERENCES filiais(id) ON DELETE RESTRICT,
  total_itens INTEGER NOT NULL,
  total_unidades_snapshot INTEGER NOT NULL,
  itens_conformes_r1 INTEGER NOT NULL DEFAULT 0,
  itens_enviados_r2 INTEGER NOT NULL DEFAULT 0,
  itens_conformes_apos_r2 INTEGER NOT NULL DEFAULT 0,
  divergencias_confirmadas INTEGER NOT NULL DEFAULT 0,
  investigacoes_resolvidas INTEGER NOT NULL DEFAULT 0,
  investigacoes_sem_causa INTEGER NOT NULL DEFAULT 0,
  investigacoes_pendentes INTEGER NOT NULL DEFAULT 0,
  itens_nao_contados INTEGER NOT NULL DEFAULT 0,
  itens_com_falta INTEGER NOT NULL DEFAULT 0,
  itens_com_sobra INTEGER NOT NULL DEFAULT 0,
  quantidade_falta INTEGER NOT NULL DEFAULT 0,
  quantidade_sobra INTEGER NOT NULL DEFAULT 0,
  inicio_em TIMESTAMPTZ,
  fechado_em TIMESTAMPTZ NOT NULL,
  duracao_segundos BIGINT,
  tipo_fechamento VARCHAR(24) NOT NULL CHECK (tipo_fechamento IN ('NORMAL', 'EXCEPCIONAL')),
  justificativa_excepcional TEXT,
  pendencias_snapshot JSONB,
  fechado_por_id TEXT,
  fechado_por_nome TEXT NOT NULL,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_resultados_inventario UNIQUE (inventario_id)
);

CREATE INDEX idx_resultados_inventario_filial ON resultados_inventario(filial_id);
CREATE INDEX idx_resultados_inventario_fechado_em ON resultados_inventario(fechado_em DESC);

-- 3. Tabela de snapshot imutável do resultado final por item
CREATE TABLE resultado_inventario_itens (
  id BIGSERIAL PRIMARY KEY,
  resultado_inventario_id BIGINT NOT NULL REFERENCES resultados_inventario(id) ON DELETE RESTRICT,
  inventario_id BIGINT NOT NULL REFERENCES inventarios(id) ON DELETE RESTRICT,
  filial_id BIGINT NOT NULL REFERENCES filiais(id) ON DELETE RESTRICT,
  inventario_item_id BIGINT NOT NULL REFERENCES inventario_itens(id) ON DELETE RESTRICT,
  sku VARCHAR(64) NOT NULL,
  descricao TEXT NOT NULL DEFAULT '',
  saldo_snapshot INTEGER NOT NULL,
  r1_fisico INTEGER,
  r1_diferenca INTEGER,
  r1_estado VARCHAR(32),
  houve_r2 BOOLEAN NOT NULL DEFAULT FALSE,
  r2_fisico INTEGER,
  r2_diferenca INTEGER,
  r2_estado VARCHAR(32),
  quantidade_fisica_final INTEGER,
  diferenca_final INTEGER,
  estado_final VARCHAR(32) NOT NULL CHECK (estado_final IN (
    'CONFORME', 'CONFORME_APOS_RECONTAGEM', 'DIVERGENCIA_CONFIRMADA', 'NAO_CONTADO'
  )),
  tipo_divergencia VARCHAR(32) NOT NULL CHECK (tipo_divergencia IN (
    'CONFORME', 'FALTA', 'SOBRA', 'NAO_CONTADO'
  )),
  investigacao_id UUID REFERENCES investigacoes_divergencia(id) ON DELETE RESTRICT,
  status_investigacao VARCHAR(32),
  causa_confirmada VARCHAR(32),
  conclusao TEXT,
  detalhes_localizacao_condicao JSONB,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_resultado_itens UNIQUE (inventario_id, inventario_item_id)
);

CREATE INDEX idx_resultado_itens_resultado_id ON resultado_inventario_itens(resultado_inventario_id);
CREATE INDEX idx_resultado_itens_inventario_id ON resultado_inventario_itens(inventario_id);
CREATE INDEX idx_resultado_itens_sku ON resultado_inventario_itens(sku);
CREATE INDEX idx_resultado_itens_estado_final ON resultado_inventario_itens(estado_final);
CREATE INDEX idx_resultado_itens_tipo_divergencia ON resultado_inventario_itens(tipo_divergencia);

-- 4. Tabela de eventos auditáveis da sessão de inventário
CREATE TABLE eventos_inventario (
  id BIGSERIAL PRIMARY KEY,
  inventario_id BIGINT NOT NULL REFERENCES inventarios(id) ON DELETE RESTRICT,
  filial_id BIGINT NOT NULL REFERENCES filiais(id) ON DELETE RESTRICT,
  tipo_evento VARCHAR(32) NOT NULL CHECK (tipo_evento IN (
    'CRIADO', 'ABERTO', 'INICIADO', 'RODADA_INICIADA', 'RODADA_ENCERRADA',
    'RECONTAGEM_CRIADA', 'INVENTARIO_ENCERRADO', 'CANCELADO'
  )),
  detalhes JSONB,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  criado_por TEXT NOT NULL
);

CREATE INDEX idx_eventos_inventario_inv_data ON eventos_inventario(inventario_id, criado_em);
