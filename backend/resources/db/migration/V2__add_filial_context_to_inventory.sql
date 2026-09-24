-- As criações compatíveis permitem aplicar V2 também em um banco novo, antes
-- que o inicializador legado complete as demais tabelas.
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

CREATE TABLE IF NOT EXISTS contagens (
  id BIGSERIAL PRIMARY KEY,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  operador TEXT NOT NULL,
  importacao_id BIGINT REFERENCES importacoes_saldo(id),
  status VARCHAR(24) NOT NULL DEFAULT 'ABERTA'
);

CREATE TABLE IF NOT EXISTS estoque_produtos (
  sku VARCHAR(64) PRIMARY KEY,
  descricao TEXT NOT NULL DEFAULT '',
  saldo_sistema INTEGER NOT NULL CHECK (saldo_sistema >= 0),
  saldo_contado INTEGER NOT NULL DEFAULT 0 CHECK (saldo_contado >= 0),
  saldo_assistencia INTEGER NOT NULL DEFAULT 0 CHECK (saldo_assistencia >= 0),
  saldo_avaria INTEGER NOT NULL DEFAULT 0 CHECK (saldo_avaria >= 0),
  saldo_outros INTEGER NOT NULL DEFAULT 0,
  ativo BOOLEAN NOT NULL DEFAULT TRUE,
  ultima_atualizacao TIMESTAMPTZ NOT NULL DEFAULT now(),
  ultima_contagem_em TIMESTAMPTZ,
  importacao_id BIGINT NOT NULL REFERENCES importacoes_saldo(id)
);

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM filiais WHERE codigo = '281') THEN
    RAISE EXCEPTION 'V2 requer a Filial 281 criada pela V1';
  END IF;
END $$;

ALTER TABLE importacoes_saldo ADD COLUMN filial_id BIGINT;
ALTER TABLE estoque_produtos ADD COLUMN filial_id BIGINT;
ALTER TABLE contagens ADD COLUMN filial_id BIGINT;

UPDATE importacoes_saldo
SET filial_id = (SELECT id FROM filiais WHERE codigo = '281')
WHERE filial_id IS NULL;

UPDATE estoque_produtos
SET filial_id = (SELECT id FROM filiais WHERE codigo = '281')
WHERE filial_id IS NULL;

UPDATE contagens
SET filial_id = (SELECT id FROM filiais WHERE codigo = '281')
WHERE filial_id IS NULL;

DO $$
DECLARE
  orphan_count BIGINT;
BEGIN
  SELECT
    (SELECT COUNT(*) FROM importacoes_saldo WHERE filial_id IS NULL)
    + (SELECT COUNT(*) FROM estoque_produtos WHERE filial_id IS NULL)
    + (SELECT COUNT(*) FROM contagens WHERE filial_id IS NULL)
  INTO orphan_count;
  IF orphan_count <> 0 THEN
    RAISE EXCEPTION 'V2 encontrou % registros raiz sem filial', orphan_count;
  END IF;
END $$;

ALTER TABLE importacoes_saldo
  ADD CONSTRAINT fk_importacoes_saldo_filial
  FOREIGN KEY (filial_id) REFERENCES filiais(id) NOT VALID;
ALTER TABLE estoque_produtos
  ADD CONSTRAINT fk_estoque_produtos_filial
  FOREIGN KEY (filial_id) REFERENCES filiais(id) NOT VALID;
ALTER TABLE contagens
  ADD CONSTRAINT fk_contagens_filial
  FOREIGN KEY (filial_id) REFERENCES filiais(id) NOT VALID;

ALTER TABLE importacoes_saldo VALIDATE CONSTRAINT fk_importacoes_saldo_filial;
ALTER TABLE estoque_produtos VALIDATE CONSTRAINT fk_estoque_produtos_filial;
ALTER TABLE contagens VALIDATE CONSTRAINT fk_contagens_filial;

CREATE INDEX idx_importacoes_saldo_filial_atualizado
  ON importacoes_saldo(filial_id, atualizado_em DESC, id DESC);
CREATE INDEX idx_estoque_produtos_filial_ativo
  ON estoque_produtos(filial_id, ativo, sku);
CREATE INDEX idx_contagens_filial_criado
  ON contagens(filial_id, criado_em DESC, id DESC);

DO $$
DECLARE
  primary_key_name TEXT;
  primary_key_columns TEXT[];
  duplicate_count BIGINT;
  dependent_foreign_keys TEXT;
BEGIN
  SELECT c.conname, array_agg(a.attname ORDER BY k.ordinality)
  INTO primary_key_name, primary_key_columns
  FROM pg_constraint c
  JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ordinality) ON TRUE
  JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
  WHERE c.conrelid = 'estoque_produtos'::regclass AND c.contype = 'p'
  GROUP BY c.conname;

  IF primary_key_name IS NULL OR primary_key_columns <> ARRAY['sku']::TEXT[] THEN
    RAISE EXCEPTION 'V2 esperava chave primária legado somente em estoque_produtos.sku; encontrada % (%)',
      primary_key_name, primary_key_columns;
  END IF;

  SELECT COUNT(*)
  INTO duplicate_count
  FROM (
    SELECT filial_id, sku
    FROM estoque_produtos
    GROUP BY filial_id, sku
    HAVING COUNT(*) > 1
  ) duplicates;
  IF duplicate_count <> 0 THEN
    RAISE EXCEPTION 'V2 encontrou % chaves duplicadas em estoque_produtos(filial_id, sku)',
      duplicate_count;
  END IF;

  SELECT string_agg(c.conname, ', ' ORDER BY c.conname)
  INTO dependent_foreign_keys
  FROM pg_constraint c
  WHERE c.contype = 'f' AND c.confrelid = 'estoque_produtos'::regclass;
  IF dependent_foreign_keys IS NOT NULL THEN
    RAISE EXCEPTION 'V2 não pode substituir a chave de estoque_produtos; FKs dependentes: %',
      dependent_foreign_keys;
  END IF;

  EXECUTE format('ALTER TABLE estoque_produtos DROP CONSTRAINT %I', primary_key_name);
END $$;

ALTER TABLE estoque_produtos
  ADD CONSTRAINT estoque_produtos_pkey PRIMARY KEY (filial_id, sku);

ALTER TABLE importacoes_saldo ALTER COLUMN filial_id SET NOT NULL;
ALTER TABLE estoque_produtos ALTER COLUMN filial_id SET NOT NULL;
ALTER TABLE contagens ALTER COLUMN filial_id SET NOT NULL;
