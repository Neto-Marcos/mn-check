-- V6: Criar estrutura para investigação e tratativa de divergências

CREATE TABLE investigacoes_divergencia (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  filial_id BIGINT NOT NULL REFERENCES filiais(id) ON DELETE RESTRICT,
  inventario_id BIGINT NOT NULL REFERENCES inventarios(id) ON DELETE RESTRICT,
  inventario_item_id BIGINT NOT NULL REFERENCES inventario_itens(id) ON DELETE RESTRICT,
  apuracao_id BIGINT NOT NULL REFERENCES apuracoes_rodada(id) ON DELETE RESTRICT,
  sku VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDENTE' CHECK (status IN (
    'PENDENTE', 'EM_INVESTIGACAO', 'AGUARDANDO_EVIDENCIA', 'RESOLVIDA', 'SEM_CAUSA_IDENTIFICADA'
  )),
  causa_suspeita VARCHAR(32) CHECK (causa_suspeita IN (
    'ERRO_CONTAGEM', 'INVERSAO_PRODUTO', 'INVERSAO_VOLTAGEM', 'AVARIA', 'ASSISTENCIA',
    'ERRO_SEPARACAO', 'MERCADORIA_CLIENTE', 'MOVIMENTACAO', 'FISCAL_NF', 'NAO_LOCALIZADO',
    'OUTRO', 'NAO_IDENTIFICADA'
  )),
  causa_confirmada VARCHAR(32) CHECK (causa_confirmada IN (
    'ERRO_CONTAGEM', 'INVERSAO_PRODUTO', 'INVERSAO_VOLTAGEM', 'AVARIA', 'ASSISTENCIA',
    'ERRO_SEPARACAO', 'MERCADORIA_CLIENTE', 'MOVIMENTACAO', 'FISCAL_NF', 'NAO_LOCALIZADO',
    'OUTRO', 'NAO_IDENTIFICADA'
  )),
  justificativa TEXT,
  conclusao TEXT,
  responsavel_id TEXT,
  responsavel_nome TEXT,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  criado_por TEXT NOT NULL,
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  atualizado_por TEXT,
  resolvido_em TIMESTAMPTZ,
  resolvido_por TEXT,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_investigacao_apuracao UNIQUE (apuracao_id)
);

CREATE INDEX idx_investigacoes_filial ON investigacoes_divergencia(filial_id);
CREATE INDEX idx_investigacoes_inventario ON investigacoes_divergencia(inventario_id);
CREATE INDEX idx_investigacoes_sku ON investigacoes_divergencia(sku);
CREATE INDEX idx_investigacoes_status ON investigacoes_divergencia(status);
CREATE INDEX idx_investigacoes_causa ON investigacoes_divergencia(causa_confirmada);

CREATE TABLE evidencias_investigacao (
  id BIGSERIAL PRIMARY KEY,
  investigacao_id UUID NOT NULL REFERENCES investigacoes_divergencia(id) ON DELETE RESTRICT,
  tipo VARCHAR(32) NOT NULL CHECK (tipo IN (
    'OBSERVACAO', 'FOTO', 'DOCUMENTO', 'NOTA_FISCAL', 'CONTAGEM', 'PRODUTO_RELACIONADO', 'OUTRO'
  )),
  descricao TEXT NOT NULL,
  referencia TEXT,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  criado_por TEXT NOT NULL
);

CREATE INDEX idx_evidencias_investigacao_id ON evidencias_investigacao(investigacao_id);

CREATE TABLE investigacao_vinculos (
  id BIGSERIAL PRIMARY KEY,
  investigacao_id UUID NOT NULL REFERENCES investigacoes_divergencia(id) ON DELETE RESTRICT,
  inventario_item_relacionado_id BIGINT NOT NULL REFERENCES inventario_itens(id) ON DELETE RESTRICT,
  apuracao_relacionada_id BIGINT REFERENCES apuracoes_rodada(id) ON DELETE RESTRICT,
  tipo_vinculo VARCHAR(32) NOT NULL CHECK (tipo_vinculo IN (
    'POSSIVEL_INVERSAO', 'POSSIVEL_VOLTAGEM', 'MESMO_PRODUTO', 'MOVIMENTACAO_RELACIONADA', 'OUTRO'
  )),
  observacao TEXT,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  criado_por TEXT NOT NULL,
  UNIQUE (investigacao_id, inventario_item_relacionado_id)
);

CREATE INDEX idx_investigacao_vinculos_inv_id ON investigacao_vinculos(investigacao_id);

CREATE TABLE eventos_investigacao (
  id BIGSERIAL PRIMARY KEY,
  investigacao_id UUID NOT NULL REFERENCES investigacoes_divergencia(id) ON DELETE RESTRICT,
  tipo_evento VARCHAR(32) NOT NULL CHECK (tipo_evento IN (
    'CRIADA', 'INICIADA', 'STATUS_ALTERADO', 'CAUSA_SUSPEITA_ALTERADA',
    'EVIDENCIA_ADICIONADA', 'PRODUTO_RELACIONADO', 'RESOLVIDA', 'ENCERRADA_SEM_CAUSA', 'REABERTA'
  )),
  detalhes JSONB,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  criado_por TEXT NOT NULL
);

CREATE INDEX idx_eventos_investigacao_id ON eventos_investigacao(investigacao_id, criado_em);
