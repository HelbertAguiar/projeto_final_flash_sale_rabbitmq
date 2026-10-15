# Demonstração independente de infraestrutura

Inicie as duas JVMs pelo [README](../README.md). Não execute com tráfego concorrente. No PowerShell, substitua curl por curl.exe. Use portas padrão ou ajuste as URLs.

## Sucesso e cache

```bash
curl -i http://localhost:8080/health/ready
curl -i http://localhost:8081/health/ready
curl -i -X POST http://localhost:8080/internal/demo/stock
curl -i -H 'Content-Type: application/json' -d '{"produto_id":"PROD-001","quantidade":2,"cliente_cpf":"50329291076","cliente_nome":"Demo sucesso"}' http://localhost:8080/v1/orders
```

Esperado: prontidão UP/mode=mock, estoque 10 e criação HTTP 202/PROCESSING. Copie pedido_id. Consulte até CONFIRMED:
```bash
curl -i http://localhost:8080/v1/orders/UUID_DO_PEDIDO
curl -i http://localhost:8080/v1/orders/UUID_DO_PEDIDO
curl -i http://localhost:8080/internal/demo/stock
```
A primeira consulta confirmada deve dar MISS e a seguinte HIT; estoque 8.

No PowerShell 5.1, grave o JSON em arquivo ASCII e envie com `--data-binary '@pedido.json'` para evitar problemas de aspas.

## Cancelamento e compensação

Registre um marcador e use o mesmo valor em cliente_nome:
```bash
curl -i -X POST http://localhost:8081/internal/demo/failures/demo-failure-00000000-0000-0000-0000-000000000001
curl -i -H 'Content-Type: application/json' -d '{"produto_id":"PROD-001","quantidade":2,"cliente_cpf":"50329291076","cliente_nome":"demo-failure-00000000-0000-0000-0000-000000000001"}' http://localhost:8080/v1/orders
```
Copie o novo UUID e consulte:
```bash
curl -i http://localhost:8080/v1/orders/UUID_CANCELADO
curl -i http://localhost:8080/internal/demo/reservations/UUID_CANCELADO
curl -i http://localhost:8080/internal/demo/stock
```
Aguarde CANCELLED, reserva COMPLETED e estoque 8. O pedido confirmado continua consumindo duas unidades.

Se a entrega de compensação falhou, após recuperar o Checkout:
```bash
curl -i -X POST http://localhost:8081/internal/mock/orders/UUID_CANCELADO/retry-compensation
```
Repetir não deve devolver estoque duas vezes.

## Automação e diagnóstico

- `bash scripts/demo-mock.sh`: sucesso, cache e cancelamento.
- `python3 scripts/test-local-flow.py`: teste completo com processos próprios; compile antes no perfil mock conforme README.
- `/internal/mock/stock/PROD-001` no Checkout inspeciona estoque.
- `/internal/mock/orders/{id}` no Processor inspeciona pedido e compensação.
- Preparação do estoque retorna 409 enquanto houver pedidos/compensações pendentes.
- Controles de demonstração retornam 404 quando não habilitados.
- Ctrl+C encerra as JVMs; uma nova execução recomeça os dados em memória.

O transporte é HTTP e os dados são voláteis; não é necessário iniciar infraestrutura.
