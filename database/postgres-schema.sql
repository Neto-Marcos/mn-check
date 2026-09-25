CREATE TABLE IF NOT EXISTS filiais (
  id BIGSERIAL PRIMARY KEY,
  codigo TEXT NOT NULL UNIQUE,
  nome TEXT NOT NULL,
  ativa BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

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
  itens_removidos INTEGER NOT NULL DEFAULT 0,
  filial_id BIGINT NOT NULL REFERENCES filiais(id)
);

CREATE TABLE IF NOT EXISTS saldos (
  id BIGSERIAL PRIMARY KEY,
  sku VARCHAR(64) NOT NULL,
  saldo INTEGER NOT NULL CHECK (saldo >= 0),
  importacao_id BIGINT NOT NULL REFERENCES importacoes_saldo(id) ON DELETE CASCADE,
  UNIQUE (importacao_id, sku)
);

CREATE TABLE IF NOT EXISTS estoque_produtos (
  filial_id BIGINT NOT NULL REFERENCES filiais(id),
  sku VARCHAR(64) NOT NULL,
  descricao TEXT NOT NULL DEFAULT '',
  saldo_sistema INTEGER NOT NULL CHECK (saldo_sistema >= 0),
  saldo_contado INTEGER NOT NULL DEFAULT 0 CHECK (saldo_contado >= 0),
  saldo_assistencia INTEGER NOT NULL DEFAULT 0 CHECK (saldo_assistencia >= 0),
  saldo_avaria INTEGER NOT NULL DEFAULT 0 CHECK (saldo_avaria >= 0),
  saldo_outros INTEGER NOT NULL DEFAULT 0,
  ativo BOOLEAN NOT NULL DEFAULT TRUE,
  ultima_atualizacao TIMESTAMPTZ NOT NULL DEFAULT now(),
  ultima_contagem_em TIMESTAMPTZ,
  importacao_id BIGINT NOT NULL REFERENCES importacoes_saldo(id),
  PRIMARY KEY (filial_id, sku)
);

CREATE TABLE IF NOT EXISTS contagens (
  id BIGSERIAL PRIMARY KEY,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  operador TEXT NOT NULL,
  importacao_id BIGINT REFERENCES importacoes_saldo(id),
  status VARCHAR(24) NOT NULL DEFAULT 'ABERTA',
  filial_id BIGINT NOT NULL REFERENCES filiais(id)
);

CREATE TABLE IF NOT EXISTS itens_contagem (
  id BIGSERIAL PRIMARY KEY,
  contagem_id BIGINT NOT NULL REFERENCES contagens(id) ON DELETE CASCADE,
  sku VARCHAR(64) NOT NULL,
  saldo_sistema INTEGER NOT NULL CHECK (saldo_sistema >= 0),
  quantidade_contada INTEGER NOT NULL CHECK (quantidade_contada >= 0),
  quantidade_assistencia INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_assistencia >= 0),
  quantidade_avaria INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_avaria >= 0),
  quantidade_outros INTEGER NOT NULL DEFAULT 0,
  diferenca INTEGER NOT NULL,
  UNIQUE (contagem_id, sku)
);

CREATE TABLE IF NOT EXISTS inventarios (
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

CREATE TABLE IF NOT EXISTS inventario_itens (
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

-- Compatibilidade com bancos criados antes da versão 2.3.1.
ALTER TABLE estoque_produtos DROP CONSTRAINT IF EXISTS estoque_produtos_saldo_outros_check;
ALTER TABLE itens_contagem DROP CONSTRAINT IF EXISTS itens_contagem_quantidade_outros_check;

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
CREATE INDEX IF NOT EXISTS idx_estoque_produtos_ativo ON estoque_produtos(filial_id, ativo, sku);
CREATE INDEX IF NOT EXISTS idx_contagens_criado_em ON contagens(criado_em DESC);
CREATE INDEX IF NOT EXISTS idx_itens_contagem_contagem ON itens_contagem(contagem_id);
CREATE INDEX IF NOT EXISTS idx_inventarios_filial_status ON inventarios(filial_id, status, criado_em DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_inventarios_importacao ON inventarios(importacao_saldo_id);
CREATE INDEX IF NOT EXISTS idx_inventario_itens_inventario ON inventario_itens(inventario_id, sku);
CREATE INDEX IF NOT EXISTS idx_conferencias_status ON conferencias(status, atualizado_em DESC);
CREATE INDEX IF NOT EXISTS idx_historico_scanner_mapa ON historico_scanner(mapa_id, criado_em DESC);

CREATE TABLE IF NOT EXISTS rodadas_contagem (
  id BIGSERIAL PRIMARY KEY,
  inventario_id BIGINT NOT NULL REFERENCES inventarios(id) ON DELETE RESTRICT,
  numero INTEGER NOT NULL CHECK (numero >= 1),
  tipo VARCHAR(24) NOT NULL DEFAULT 'CONTAGEM' CHECK (tipo IN ('CONTAGEM', 'RECONTAGEM', 'INVESTIGACAO')),
  status VARCHAR(24) NOT NULL DEFAULT 'EM_ANDAMENTO' CHECK (status IN ('EM_ANDAMENTO', 'FINALIZADA', 'CANCELADA')),
  iniciada_por TEXT NOT NULL,
  iniciada_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  finalizada_por TEXT,
  finalizada_em TIMESTAMPTZ,
  encerramento_forcado BOOLEAN NOT NULL DEFAULT FALSE,
  pendentes_no_fechamento INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (inventario_id, numero)
);

CREATE TABLE IF NOT EXISTS ocorrencias_contagem (
  id BIGSERIAL PRIMARY KEY,
  rodada_id BIGINT NOT NULL REFERENCES rodadas_contagem(id) ON DELETE RESTRICT,
  inventario_item_id BIGINT NOT NULL REFERENCES inventario_itens(id) ON DELETE RESTRICT,
  sku VARCHAR(64) NOT NULL,
  quantidade INTEGER NOT NULL CHECK (quantidade >= 0),
  localizacao VARCHAR(32) NOT NULL DEFAULT 'GERAL' CHECK (localizacao IN ('GERAL', 'VENDAS', 'DEPOSITO', 'TROCAS', 'OUTRO')),
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

CREATE INDEX IF NOT EXISTS idx_rodadas_contagem_inventario ON rodadas_contagem(inventario_id, status, numero);
CREATE INDEX IF NOT EXISTS idx_ocorrencias_rodada_item ON ocorrencias_contagem(rodada_id, inventario_item_id);
CREATE INDEX IF NOT EXISTS idx_ocorrencias_rodada_sku ON ocorrencias_contagem(rodada_id, sku);
CREATE INDEX IF NOT EXISTS idx_ocorrencias_server_timestamp ON ocorrencias_contagem(server_timestamp DESC);

CREATE TABLE IF NOT EXISTS rodada_itens (
  id BIGSERIAL PRIMARY KEY,
  rodada_id BIGINT NOT NULL REFERENCES rodadas_contagem(id) ON DELETE RESTRICT,
  inventario_item_id BIGINT NOT NULL REFERENCES inventario_itens(id) ON DELETE RESTRICT,
  origem_motivo VARCHAR(32) NOT NULL CHECK (origem_motivo IN ('DIVERGENCIA', 'NAO_CONTADO_R1', 'MANUAL')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (rodada_id, inventario_item_id)
);

CREATE INDEX IF NOT EXISTS idx_rodada_itens_item ON rodada_itens(rodada_id, inventario_item_id);

CREATE TABLE IF NOT EXISTS apuracoes_rodada (
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

CREATE INDEX IF NOT EXISTS idx_apuracoes_rodada_estado ON apuracoes_rodada(rodada_id, estado);

CREATE TABLE IF NOT EXISTS investigacoes_divergencia (
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

CREATE INDEX IF NOT EXISTS idx_investigacoes_filial ON investigacoes_divergencia(filial_id);
CREATE INDEX IF NOT EXISTS idx_investigacoes_inventario ON investigacoes_divergencia(inventario_id);
CREATE INDEX IF NOT EXISTS idx_investigacoes_sku ON investigacoes_divergencia(sku);
CREATE INDEX IF NOT EXISTS idx_investigacoes_status ON investigacoes_divergencia(status);
CREATE INDEX IF NOT EXISTS idx_investigacoes_causa ON investigacoes_divergencia(causa_confirmada);

CREATE TABLE IF NOT EXISTS evidencias_investigacao (
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

CREATE INDEX IF NOT EXISTS idx_evidencias_investigacao_id ON evidencias_investigacao(investigacao_id);

CREATE TABLE IF NOT EXISTS investigacao_vinculos (
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

CREATE INDEX IF NOT EXISTS idx_investigacao_vinculos_inv_id ON investigacao_vinculos(investigacao_id);

CREATE TABLE IF NOT EXISTS eventos_investigacao (
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

CREATE INDEX IF NOT EXISTS idx_eventos_investigacao_id ON eventos_investigacao(investigacao_id, criado_em);

-- V7: Fechamento formal, resultado consolidado, snapshot por item e eventos de inventário
ALTER TABLE inventarios DROP CONSTRAINT IF EXISTS inventarios_status_check;
ALTER TABLE inventarios ADD CONSTRAINT inventarios_status_check CHECK (status IN (
  'RASCUNHO', 'ABERTO', 'EM_CONTAGEM', 'EM_RECONTAGEM',
  'EM_INVESTIGACAO', 'FINALIZADO', 'ENCERRADO', 'CANCELADO'
));

ALTER TABLE inventarios
  ADD COLUMN IF NOT EXISTS tipo_fechamento VARCHAR(24) CHECK (tipo_fechamento IN ('NORMAL', 'EXCEPCIONAL')),
  ADD COLUMN IF NOT EXISTS justificativa_fechamento TEXT;

CREATE TABLE IF NOT EXISTS resultados_inventario (
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

CREATE INDEX IF NOT EXISTS idx_resultados_inventario_filial ON resultados_inventario(filial_id);
CREATE INDEX IF NOT EXISTS idx_resultados_inventario_fechado_em ON resultados_inventario(fechado_em DESC);

CREATE TABLE IF NOT EXISTS resultado_inventario_itens (
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

CREATE INDEX IF NOT EXISTS idx_resultado_itens_resultado_id ON resultado_inventario_itens(resultado_inventario_id);
CREATE INDEX IF NOT EXISTS idx_resultado_itens_inventario_id ON resultado_inventario_itens(inventario_id);
CREATE INDEX IF NOT EXISTS idx_resultado_itens_sku ON resultado_inventario_itens(sku);
CREATE INDEX IF NOT EXISTS idx_resultado_itens_estado_final ON resultado_inventario_itens(estado_final);
CREATE INDEX IF NOT EXISTS idx_resultado_itens_tipo_divergencia ON resultado_inventario_itens(tipo_divergencia);

CREATE TABLE IF NOT EXISTS eventos_inventario (
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

CREATE INDEX IF NOT EXISTS idx_eventos_inventario_inv_data ON eventos_inventario(inventario_id, criado_em);



