# Recuperação da produção 2.3.0

A versão anterior foi preservada na branch `codex/recovered-2.3.0` e na tag `v2.3.0-recovered` antes da reformulação empresarial.

O frontend desse marco é a cópia exata extraída do artefato implantado. A classe compilada `br.com.mncheck.AppInfo` confirmou a versão `2.3.0`. O JAR recuperado possui SHA-256 `EB00FB9ABF209004BC12E5A438EA20CDAF75AD62BD67004D3C4FB1FD82470FEF` e permanece no backup externo, sem inflar o histórico Git.

O marco recuperado compila e sua suíte registra 12 testes, sem falhas. Uma restauração operacional ainda exige o backup PostgreSQL correspondente e as variáveis de ambiente documentadas em `DEPLOYMENT.md`.
