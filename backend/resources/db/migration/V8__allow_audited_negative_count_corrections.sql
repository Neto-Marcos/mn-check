ALTER TABLE ocorrencias_contagem
  DROP CONSTRAINT ocorrencias_contagem_quantidade_check;

ALTER TABLE ocorrencias_contagem
  ADD CONSTRAINT ocorrencias_contagem_quantidade_check
  CHECK (quantidade >= 0 OR tipo_acao = 'SOMAR');
