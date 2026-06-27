# Deploy do MN Check no Railway

O MN Check esta pronto para deploy no Railway usando `Dockerfile`.

## Configuracao rapida

No Railway, crie um projeto a partir do repositorio:

```text
Neto-Marcos/mn-check
```

O Railway deve detectar:

- `Dockerfile`
- `railway.json`
- healthcheck em `/api/health`
- restart automatico em falha

## Variaveis obrigatorias

Configure em **Variables**:

```env
DATABASE_URL=postgresql://usuario:senha@host/neondb?sslmode=require
MMCHECK_ADMIN_PASSWORD=sua-senha-admin
```

## Variaveis opcionais

```env
GEMINI_API_KEY=sua-chave
GEMINI_MODEL=gemini-2.5-flash
```

## Porta

Nao fixe `PORT` manualmente no Railway. O container usa `4137` por padrao e tambem respeita a variavel `PORT` quando o Railway injeta.

Ao gerar dominio publico em **Networking**, use a porta:

```text
4137
```

## Reducao de shutdown

O Railway tende a reduzir problemas de "sleep" por inatividade, mas o servico ainda depende de plano, creditos, limites e saude do deploy.

Para reduzir indisponibilidade:

- mantenha `DATABASE_URL` apontando para o Neon;
- confirme que `/api/health` retorna `status: ok`;
- mantenha restart policy ativa via `railway.json`;
- acompanhe os logs depois de cada deploy.

## Teste apos deploy

Troque a URL pelo dominio gerado pelo Railway:

```text
https://seu-app.up.railway.app/api/health
https://seu-app.up.railway.app/api/version
```
