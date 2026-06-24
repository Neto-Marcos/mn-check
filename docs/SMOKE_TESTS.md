# Smoke tests do MN Check

Estes testes validam rapidamente os fluxos principais por HTTP, sem refatorar regra de negócio.

## O que cobre

- login;
- dashboard principal (`/api/bootstrap`);
- upload de PDF de saldo (`/api/importar`);
- atualização/persistência de saldo (`/api/saldos`);
- início de contagem;
- salvar contagem (`/api/contagem`);
- conferência por código (`/api/scanner/validate`).

## Como rodar

Com o MN Check já ligado localmente:

```powershell
$env:MM_CHECK_BASE_URL="http://127.0.0.1:4173"
$env:MM_CHECK_SMOKE_USER="Marcos"
$env:MM_CHECK_SMOKE_PASSWORD="sua-senha"
node scripts/smoke-test.mjs
```

Contra o Render:

```powershell
$env:MM_CHECK_BASE_URL="https://mn-check.onrender.com"
$env:MM_CHECK_SMOKE_USER="Marcos"
$env:MM_CHECK_SMOKE_PASSWORD="sua-senha"
node scripts/smoke-test.mjs
```

## Observações

O teste é propositalmente pequeno e operacional. Ele cria dados de teste no banco:

- uma importação de saldo com um PDF mínimo;
- uma contagem;
- um mapa temporário para validar leitura por código.

Use em ambiente de desenvolvimento, homologação ou em produção apenas quando aceitar esses registros de teste.
