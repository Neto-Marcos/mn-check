# Homologação do MN Check 3.0

## Preparação

- [ ] Backup criado, checksum validado e listagem do dump legível.
- [ ] Ambiente de homologação usa banco separado da produção.
- [ ] Filiais, produtos, códigos internos, EANs e usuários de teste cadastrados.
- [ ] Impressora Zebra/Argox configurada com etiqueta 60 × 40 mm.

## Fluxo completo

- [ ] Importar duas NF-e na mesma carga e bloquear XML repetido.
- [ ] Ler EAN válido, EAN desconhecido, falta e excesso.
- [ ] Registrar avaria com motivo e autorizar falta em separação/reconferência.
- [ ] Confirmar entrada regular e quarentena nos saldos corretos.
- [ ] Imprimir etiqueta e registrar reimpressão com justificativa.
- [ ] Salvar impressora e parâmetros próprios de cada filial.
- [ ] Publicar mapa revisado e confirmar a troca disponível → reservado.
- [ ] Impedir duas reservas acima do saldo disponível.
- [ ] Separar, reconferir e expedir o mapa completo.
- [ ] Confirmar a baixa física somente na expedição.
- [ ] Transferir entre duas filiais e conferir o estado em trânsito.
- [ ] Receber transferência divergente e resolver a quarentena.
- [ ] Publicar saldo inicial por contagem e ajuste posterior aprovado.
- [ ] Estornar um movimento e preservar o lançamento original.
- [ ] Executar reconciliação com resultado `ok: true`.

## Resiliência e segurança

- [ ] Reenviar a mesma chave de idempotência sem duplicar saldo.
- [ ] Trabalhar sem Wi-Fi e sincronizar a fila ao reconectar.
- [ ] Confirmar isolamento de filial para operadores e supervisores.
- [ ] Confirmar acesso somente leitura do auditor.
- [ ] Rejeitar XML com DTD/entidade externa.
- [ ] Testar desktop, tablet e coletor sem overflow ou erro de console.
- [ ] Restaurar o backup em banco descartável e validar login/contagem.

## Aprovação

Registrar data, filial, responsáveis de recebimento, separação, expedição, estoque e supervisor. O deploy de produção só pode ocorrer depois de todas as caixas obrigatórias aprovadas.
