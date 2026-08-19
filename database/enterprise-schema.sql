CREATE TABLE IF NOT EXISTS filiais (
  id UUID PRIMARY KEY,
  codigo VARCHAR(32) NOT NULL UNIQUE,
  nome VARCHAR(160) NOT NULL,
  cnpj VARCHAR(18) NOT NULL DEFAULT '',
  ativa BOOLEAN NOT NULL DEFAULT TRUE,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS perfis_empresariais (
  usuario_id TEXT PRIMARY KEY,
  filial_id UUID REFERENCES filiais(id),
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS produtos (
  id UUID PRIMARY KEY,
  sku VARCHAR(64) NOT NULL UNIQUE,
  codigo_interno VARCHAR(64) NOT NULL UNIQUE,
  descricao TEXT NOT NULL,
  ativo BOOLEAN NOT NULL DEFAULT TRUE,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS produtos_eans (
  ean VARCHAR(32) PRIMARY KEY,
  produto_id UUID NOT NULL REFERENCES produtos(id) ON DELETE CASCADE,
  principal BOOLEAN NOT NULL DEFAULT FALSE,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS saldos_estoque (
  filial_id UUID NOT NULL REFERENCES filiais(id),
  produto_id UUID NOT NULL REFERENCES produtos(id),
  fisico INTEGER NOT NULL DEFAULT 0 CHECK (fisico >= 0),
  disponivel INTEGER NOT NULL DEFAULT 0 CHECK (disponivel >= 0),
  reservado INTEGER NOT NULL DEFAULT 0 CHECK (reservado >= 0),
  em_transito INTEGER NOT NULL DEFAULT 0 CHECK (em_transito >= 0),
  quarentena INTEGER NOT NULL DEFAULT 0 CHECK (quarentena >= 0),
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (filial_id, produto_id),
  CHECK (disponivel + reservado + quarentena <= fisico)
);

CREATE TABLE IF NOT EXISTS movimentos_estoque (
  id UUID PRIMARY KEY,
  filial_id UUID NOT NULL REFERENCES filiais(id),
  produto_id UUID NOT NULL REFERENCES produtos(id),
  tipo VARCHAR(40) NOT NULL,
  delta_fisico INTEGER NOT NULL DEFAULT 0,
  delta_disponivel INTEGER NOT NULL DEFAULT 0,
  delta_reservado INTEGER NOT NULL DEFAULT 0,
  delta_transito INTEGER NOT NULL DEFAULT 0,
  delta_quarentena INTEGER NOT NULL DEFAULT 0,
  origem_tipo VARCHAR(40) NOT NULL,
  origem_id TEXT NOT NULL,
  idempotencia VARCHAR(160) NOT NULL UNIQUE,
  estorno_de UUID REFERENCES movimentos_estoque(id),
  motivo TEXT NOT NULL DEFAULT '',
  criado_por TEXT NOT NULL,
  dispositivo VARCHAR(120) NOT NULL DEFAULT '',
  ocorrido_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  ocorrido_cliente_em TIMESTAMPTZ,
  metadados JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS recebimentos (
  id UUID PRIMARY KEY,
  filial_id UUID NOT NULL REFERENCES filiais(id),
  referencia VARCHAR(80) NOT NULL,
  fornecedor TEXT NOT NULL DEFAULT '',
  status VARCHAR(32) NOT NULL DEFAULT 'RASCUNHO',
  criado_por TEXT NOT NULL,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  finalizado_em TIMESTAMPTZ,
  UNIQUE (filial_id, referencia)
);

CREATE TABLE IF NOT EXISTS recebimentos_documentos (
  id UUID PRIMARY KEY,
  recebimento_id UUID NOT NULL REFERENCES recebimentos(id) ON DELETE CASCADE,
  chave_nfe VARCHAR(44) NOT NULL UNIQUE,
  hash_xml VARCHAR(64) NOT NULL UNIQUE,
  nome_arquivo TEXT NOT NULL,
  emitente TEXT NOT NULL DEFAULT '',
  emitente_cnpj VARCHAR(14) NOT NULL DEFAULT '',
  destinatario TEXT NOT NULL DEFAULT '',
  destinatario_cnpj VARCHAR(14) NOT NULL DEFAULT '',
  emitida_em TIMESTAMPTZ,
  importado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE recebimentos_documentos ADD COLUMN IF NOT EXISTS emitente_cnpj VARCHAR(14) NOT NULL DEFAULT '';
ALTER TABLE recebimentos_documentos ADD COLUMN IF NOT EXISTS destinatario_cnpj VARCHAR(14) NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS recebimentos_itens (
  id UUID PRIMARY KEY,
  recebimento_id UUID NOT NULL REFERENCES recebimentos(id) ON DELETE CASCADE,
  documento_id UUID REFERENCES recebimentos_documentos(id) ON DELETE CASCADE,
  produto_id UUID REFERENCES produtos(id),
  sku_informado VARCHAR(64) NOT NULL DEFAULT '',
  codigo_interno VARCHAR(64) NOT NULL DEFAULT '',
  ean VARCHAR(32) NOT NULL DEFAULT '',
  descricao TEXT NOT NULL DEFAULT '',
  quantidade_esperada INTEGER NOT NULL CHECK (quantidade_esperada > 0),
  quantidade_recebida INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_recebida >= 0),
  quantidade_avariada INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_avariada >= 0),
  quantidade_quarentena INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_quarentena >= 0),
  status VARCHAR(32) NOT NULL DEFAULT 'PENDENTE',
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE recebimentos_itens ADD COLUMN IF NOT EXISTS quantidade_avariada INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS mapas_separacao (
  id UUID PRIMARY KEY,
  filial_id UUID NOT NULL REFERENCES filiais(id),
  numero_mapa VARCHAR(80) NOT NULL,
  pedidos TEXT NOT NULL DEFAULT '',
  cliente TEXT NOT NULL DEFAULT '',
  rota TEXT NOT NULL DEFAULT '',
  arquivo_nome TEXT NOT NULL DEFAULT '',
  arquivo_hash VARCHAR(64) NOT NULL DEFAULT '',
  status VARCHAR(32) NOT NULL DEFAULT 'RASCUNHO',
  criado_por TEXT NOT NULL,
  revisado_por TEXT,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  publicado_em TIMESTAMPTZ,
  finalizado_em TIMESTAMPTZ,
  UNIQUE (filial_id, numero_mapa)
);

CREATE TABLE IF NOT EXISTS mapas_separacao_itens (
  id UUID PRIMARY KEY,
  mapa_id UUID NOT NULL REFERENCES mapas_separacao(id) ON DELETE CASCADE,
  produto_id UUID NOT NULL REFERENCES produtos(id),
  quantidade_esperada INTEGER NOT NULL CHECK (quantidade_esperada > 0),
  quantidade_separada INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_separada >= 0),
  quantidade_conferida INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_conferida >= 0),
  quantidade_cancelada INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_cancelada >= 0),
  status VARCHAR(32) NOT NULL DEFAULT 'PENDENTE',
  UNIQUE (mapa_id, produto_id)
);

ALTER TABLE mapas_separacao_itens ADD COLUMN IF NOT EXISTS quantidade_cancelada INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS reservas_estoque (
  id UUID PRIMARY KEY,
  filial_id UUID NOT NULL REFERENCES filiais(id),
  produto_id UUID NOT NULL REFERENCES produtos(id),
  mapa_id UUID NOT NULL REFERENCES mapas_separacao(id),
  item_id UUID NOT NULL REFERENCES mapas_separacao_itens(id),
  quantidade INTEGER NOT NULL CHECK (quantidade > 0),
  consumida INTEGER NOT NULL DEFAULT 0 CHECK (consumida >= 0),
  liberada INTEGER NOT NULL DEFAULT 0 CHECK (liberada >= 0),
  status VARCHAR(24) NOT NULL DEFAULT 'ATIVA',
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (item_id)
);

CREATE TABLE IF NOT EXISTS transferencias (
  id UUID PRIMARY KEY,
  referencia VARCHAR(80) NOT NULL UNIQUE,
  filial_origem_id UUID NOT NULL REFERENCES filiais(id),
  filial_destino_id UUID NOT NULL REFERENCES filiais(id),
  status VARCHAR(32) NOT NULL DEFAULT 'RASCUNHO',
  veiculo TEXT NOT NULL DEFAULT '',
  rota TEXT NOT NULL DEFAULT '',
  criado_por TEXT NOT NULL,
  aprovado_por TEXT,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  expedido_em TIMESTAMPTZ,
  recebido_em TIMESTAMPTZ,
  CHECK (filial_origem_id <> filial_destino_id)
);

CREATE TABLE IF NOT EXISTS transferencias_itens (
  id UUID PRIMARY KEY,
  transferencia_id UUID NOT NULL REFERENCES transferencias(id) ON DELETE CASCADE,
  produto_id UUID NOT NULL REFERENCES produtos(id),
  quantidade INTEGER NOT NULL CHECK (quantidade > 0),
  quantidade_recebida INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_recebida >= 0),
  quantidade_quarentena INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_quarentena >= 0),
  UNIQUE (transferencia_id, produto_id)
);

CREATE TABLE IF NOT EXISTS divergencias_operacionais (
  id UUID PRIMARY KEY,
  filial_id UUID NOT NULL REFERENCES filiais(id),
  operacao_tipo VARCHAR(32) NOT NULL,
  operacao_id UUID NOT NULL,
  item_id UUID,
  tipo VARCHAR(32) NOT NULL,
  quantidade INTEGER NOT NULL DEFAULT 0,
  descricao TEXT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ABERTA',
  resolucao VARCHAR(32),
  justificativa TEXT,
  criado_por TEXT NOT NULL,
  resolvido_por TEXT,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolvido_em TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS impressoras (
  id UUID PRIMARY KEY,
  filial_id UUID NOT NULL REFERENCES filiais(id),
  nome VARCHAR(120) NOT NULL,
  fabricante VARCHAR(32) NOT NULL DEFAULT 'NAVEGADOR',
  largura_mm INTEGER NOT NULL DEFAULT 60 CHECK (largura_mm > 0),
  altura_mm INTEGER NOT NULL DEFAULT 40 CHECK (altura_mm > 0),
  dpi INTEGER NOT NULL DEFAULT 203 CHECK (dpi > 0),
  ativa BOOLEAN NOT NULL DEFAULT TRUE,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (filial_id, nome)
);

CREATE TABLE IF NOT EXISTS parametros_operacionais (
  filial_id UUID NOT NULL REFERENCES filiais(id),
  chave VARCHAR(80) NOT NULL,
  valor JSONB NOT NULL DEFAULT '{}'::jsonb,
  atualizado_por TEXT NOT NULL,
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (filial_id, chave)
);

CREATE TABLE IF NOT EXISTS trabalhos_impressao (
  id UUID PRIMARY KEY,
  filial_id UUID NOT NULL REFERENCES filiais(id),
  produto_id UUID NOT NULL REFERENCES produtos(id),
  impressora_id UUID REFERENCES impressoras(id),
  origem_tipo VARCHAR(32) NOT NULL,
  origem_id UUID NOT NULL,
  quantidade_etiquetas INTEGER NOT NULL CHECK (quantidade_etiquetas > 0),
  largura_mm INTEGER NOT NULL DEFAULT 60,
  altura_mm INTEGER NOT NULL DEFAULT 40,
  dpi INTEGER NOT NULL DEFAULT 203,
  reimpressao BOOLEAN NOT NULL DEFAULT FALSE,
  motivo TEXT NOT NULL DEFAULT '',
  criado_por TEXT NOT NULL,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE trabalhos_impressao ADD COLUMN IF NOT EXISTS impressora_id UUID REFERENCES impressoras(id);

CREATE TABLE IF NOT EXISTS auditoria_empresarial (
  id BIGSERIAL PRIMARY KEY,
  filial_id UUID REFERENCES filiais(id),
  usuario_id TEXT NOT NULL,
  acao VARCHAR(80) NOT NULL,
  entidade_tipo VARCHAR(40) NOT NULL,
  entidade_id TEXT NOT NULL,
  detalhes JSONB NOT NULL DEFAULT '{}'::jsonb,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS requisicoes_idempotentes (
  chave VARCHAR(160) PRIMARY KEY,
  usuario_id TEXT NOT NULL,
  operacao VARCHAR(80) NOT NULL,
  entidade_tipo VARCHAR(40) NOT NULL,
  entidade_id TEXT NOT NULL,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_movimentos_filial_produto ON movimentos_estoque (filial_id, produto_id, ocorrido_em DESC);
CREATE INDEX IF NOT EXISTS idx_movimentos_origem ON movimentos_estoque (origem_tipo, origem_id);
CREATE INDEX IF NOT EXISTS idx_recebimentos_filial_status ON recebimentos (filial_id, status, atualizado_em DESC);
CREATE INDEX IF NOT EXISTS idx_recebimentos_itens_recebimento ON recebimentos_itens (recebimento_id);
CREATE INDEX IF NOT EXISTS idx_mapas_filial_status ON mapas_separacao (filial_id, status, criado_em DESC);
CREATE INDEX IF NOT EXISTS idx_transferencias_filiais ON transferencias (filial_origem_id, filial_destino_id, criado_em DESC);
CREATE INDEX IF NOT EXISTS idx_divergencias_status ON divergencias_operacionais (filial_id, status, criado_em DESC);
CREATE INDEX IF NOT EXISTS idx_auditoria_empresarial ON auditoria_empresarial (filial_id, criado_em DESC);
CREATE INDEX IF NOT EXISTS idx_impressoras_filial ON impressoras (filial_id, ativa, nome);
CREATE UNIQUE INDEX IF NOT EXISTS idx_mapas_arquivo_hash_unique
  ON mapas_separacao (arquivo_hash) WHERE arquivo_hash <> '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_auditoria_idempotencia_unique
  ON auditoria_empresarial ((detalhes ->> 'idempotencia')) WHERE detalhes ? 'idempotencia';

INSERT INTO filiais (id, codigo, nome)
VALUES ('00000000-0000-0000-0000-000000000001', 'MATRIZ', 'Matriz')
ON CONFLICT (codigo) DO NOTHING;
