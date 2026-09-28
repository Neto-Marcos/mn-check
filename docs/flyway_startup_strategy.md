# Flyway: startup e migrations futuras

## Diagnóstico

O candidato 145c46b configura datasource, Flyway enabled e validate-on-migrate em MnCheckApplication, e só inicia o servidor legado após o contexto Spring. Não há profile ou runner alternativo que execute migrations. Portanto o startup automático é intenção da arquitetura, não uma operação manual intencional.

Entretanto o classpath não incluía starter JDBC, pool de conexões ou spring-jdbc. As propriedades spring.datasource não criavam um DataSource utilizável pela autoconfiguração Flyway. Habilitar SPRING_FLYWAY_ENABLED no Railway isolado não produziu histórico nem logs Flyway. A correção mínima adiciona spring-boot-starter-jdbc (incluindo HikariCP e spring-jdbc); não altera V1–V7, versão, auth ou serviços de domínio.

## Banco legado reconciliado

Baseline inicial V7 continua explícito e controlado, somente depois de equivalência e preservação comprovadas. O clone descartável já tem baseline V7. Na validação do novo candidato usar SPRING_FLYWAY_ENABLED=true, SPRING_FLYWAY_BASELINE_ON_MIGRATE=false e SPRING_FLYWAY_VALIDATE_ON_MIGRATE=true. Isso exige histórico já existente em schema não vazio: ausência de histórico não deve gerar baseline implícito.

Os defaults legados baseline-on-migrate=true/baseline-version=0 permanecem inalterados para não mudar o contrato de inicialização de bancos vazios/legados do CI. NÃO iniciar o novo candidato contra produção sem baseline V7 previamente autorizado e conferido. Este documento não altera nem autoriza variáveis em produção.

## V8 e posteriores

Com histórico V7 existente, Flyway valida migrations versionadas e aplica somente versões pendentes durante o contexto Spring, antes de iniciar o legado. Falha de validação/migration impede startup. V1–V7 não são reaplicadas; não alterar migrations já versionadas. Mudanças futuras exigem nova migration revisada, testes reais PostgreSQL, backup, aprovação e janela controlada. Nenhuma V8 foi criada nesta tarefa.

## Testes

FlywayStartupAutoConfigurationTest cobre presença de DataSource/Flyway/initializer, invocação no startup, desabilitação explícita e falha fechada. A estratégia sem acesso ao banco existe somente no teste unitário; a aplicação usa o initializer padrão real. CI testa migrations em PostgreSQL descartável e inicia o JAR real. Logs e histórico do Railway descartável devem confirmar validação e zero migrations pendentes após baseline V7.

Referências oficiais: https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/autoconfigure/flyway/FlywayAutoConfiguration.html e https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/autoconfigure/jdbc/DataSourceAutoConfiguration.html
