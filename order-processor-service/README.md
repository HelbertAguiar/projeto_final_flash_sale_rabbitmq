# Order Processor Service

MVP da seção 4.4 em Java 21 / Quarkus / Maven. Recebe `order-created` por HTTP, enfileira e responde 202. O consumidor assíncrono registra PROCESSING e CONFIRMED/CANCELLED; no cancelamento publica `order-compensation` ao Checkout. Repositório e fila são independentes e em memória.

Na raiz do repositório:

```bash
mvn -f order-processor-service/pom.xml quarkus:dev
mvn -f order-processor-service/pom.xml test
```

Porta padrão: 8081. Falhas configuráveis: `-Dprocessor.mock.failure-mode=always` (padrão `never`).

Veja o [README principal](../README.md) para portas, URLs, IntelliJ, demonstrações, consulta interna, retry de compensações pendentes e testes HTTP entre processos.
