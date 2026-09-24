CREATE TABLE IF NOT EXISTS filiais (
  id BIGSERIAL PRIMARY KEY,
  codigo TEXT NOT NULL,
  nome TEXT NOT NULL,
  ativa BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_filiais_codigo UNIQUE (codigo)
);

INSERT INTO filiais (codigo, nome, ativa)
VALUES ('281', 'Filial 281', TRUE)
ON CONFLICT (codigo) DO NOTHING;
