# Arquitetura do MM Check

O MM Check foi construído como um sistema operacional de logística com backend Java, interface web responsiva e persistência PostgreSQL.

## Camadas Atuais

- **Frontend**: `frontend/app.js`, `frontend/styles.css` e service worker.
- **Backend HTTP**: `backend/src/MmCheckServer.java`.
- **Scanner**: `BarcodeParser`, `BarcodeValidationService` e `ScannerController`.
- **PDF**: `BalancePdfParser`, baseado em Apache PDFBox.
- **Persistência**: `PostgresDatabase`, usando JDBC e migrations automáticas.
- **Banco**: Neon/PostgreSQL.

## Decisões Técnicas

- O parser principal de saldo não depende de IA.
- SKUs duplicados no PDF têm saldo somado.
- Produtos removidos do PDF são marcados como inativos, não apagados.
- Contagens preservam snapshot do saldo usado naquele momento.
- Conferências podem ser salvas, pausadas, retomadas ou canceladas com confirmação.

## Evolução Recomendada

Para uma versão empresarial maior, separar:

- `controllers/`
- `services/`
- `repositories/`
- `models/`
- `dtos/`
- `configs/`
- `utils/`

Essa separação deve ser feita em etapas para não quebrar o deploy atual.
