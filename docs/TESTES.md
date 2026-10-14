# Demonstração manual dos 12 requisitos

Use o ambiente **REAL** iniciado conforme o [README](../README.md). Execute uma etapa por vez, na raiz do projeto, sem tráfego concorrente. Os comandos não formam um script automático. Mantenha as variáveis no mesmo terminal. Os exemplos pressupõem portas e credenciais padrão.

Nos blocos `bash` com `curl`, PowerShell deve usar **`curl.exe`** (evita o alias de `Invoke-WebRequest` no Windows PowerShell 5.1). Comandos `docker compose` e comandos com `$pedido`/`$falha` funcionam nos dois shells depois de definir as variáveis conforme indicado. Arquivos JSON temporários evitam problemas de aspas no PowerShell 5.1.

Abra http://localhost:15672, entre com `flashsale` / `flashsale`, selecione vhost `/flashsale`. Na aba **Queues and Streams**, use a fila indicada → **Get messages**, quantidade 1, **Ack mode: Nack message requeue true**. Isso permite ver o JSON e recolocá-lo na fila. Não use purge nem modo sem requeue. O contador `Consumers=0` é normal: o código busca mensagens por `basicGet` a cada segundo.

## 1. Estoque inicial

Com ambos os serviços saudáveis, prepare o produto de demonstração:

```bash
curl -i -X POST http://localhost:8080/internal/demo/stock
```

Esperado: HTTP 200 e `disponivel=10`. Este comando altera somente o saldo de `PROD-001`; não apaga pedidos. HTTP 409 significa que há pedidos/compensações pendentes: conclua-os antes de repetir. Confira diretamente no Redis:

```text
docker compose exec -T redis redis-cli GET stock:PROD-001
```

Esperado: `10`. Pause o consumidor para observar a próxima publicação:

```text
docker compose pause order-processor-service
```

## 2. Chamada POST /v1/orders

PowerShell:
```powershell
New-Item -ItemType Directory -Force .run | Out-Null
'{"produto_id":"PROD-001","quantidade":2,"cliente_cpf":"50329291076","cliente_nome":"Manual sucesso"}' | Set-Content -Encoding ASCII .run/pedido.json
curl.exe -sS -D .run/headers.txt -o .run/resposta.json -H 'Content-Type: application/json' --data-binary '@.run/pedido.json' http://localhost:8080/v1/orders
```

Bash:
```bash
mkdir -p .run
printf '%s' '{"produto_id":"PROD-001","quantidade":2,"cliente_cpf":"50329291076","cliente_nome":"Manual sucesso"}' > .run/pedido.json
curl -sS -D .run/headers.txt -o .run/resposta.json -H 'Content-Type: application/json' --data-binary @.run/pedido.json http://localhost:8080/v1/orders
```

O Checkout reserva duas unidades e aceita o pedido mesmo com o Processor pausado.

## 3. Resposta 202 e identificador

PowerShell:
```powershell
Get-Content .run/headers.txt
Get-Content .run/resposta.json
$pedido = (Get-Content .run/resposta.json -Raw | ConvertFrom-Json).pedido_id
$pedido
```

Bash:
```bash
cat .run/headers.txt .run/resposta.json
```

Copie o UUID exibido e defina no Bash:
```bash
pedido='COLE_O_UUID_RECEBIDO'
```

Esperado: HTTP **202 Accepted**, `pedido_id` UUID e `status=PROCESSING`. Não reenvie o POST para consultar: cada envio gera outra compra.

## 4. Publicação em order-created

Aguarde alguns segundos, execute:
```text
docker compose exec -T rabbitmq rabbitmqctl list_queues -p /flashsale name messages_ready messages_unacknowledged
docker compose exec -T checkout-db psql -U checkout -d checkout -c "SELECT pedido_id, state FROM checkout_outbox WHERE pedido_id='$pedido';"
```

Esperado: `order-created` contém uma mensagem pronta e o outbox do UUID está `SENT`. Na interface RabbitMQ, inspecione **order-created** usando **Get messages com requeue**, conforme orientação inicial. Confira `pedido_id`, produto e quantidade no payload. Outbox `SENT` confirma publicação; o payload da fila mostra o evento concreto.

## 5. Consumo pelo order-processor-service

```text
docker compose unpause order-processor-service
docker compose logs --since 2m order-processor-service
```

Aguarde o ciclo do worker e repita os logs se necessário. Procure o UUID e `CONFIRMED`. Consulte novamente as filas:
```text
docker compose exec -T rabbitmq rabbitmqctl list_queues -p /flashsale name messages_ready messages_unacknowledged
```

Esperado: `order-created` volta a zero após processamento e ack. A fila vazia isoladamente não comprova sucesso; combine com logs e banco no próximo passo.

## 6. Persistência CONFIRMED

```text
docker compose exec -T processor-db psql -U processor -d processor -c "SELECT pedido_id, payload::jsonb->>'status' AS status, payload FROM orders WHERE pedido_id='$pedido';"
docker compose exec -T redis redis-cli GET stock:PROD-001
```

Esperado: uma linha `CONFIRMED` no PostgreSQL do Processor e estoque `8`. O histórico no payload inclui `PROCESSING` e `CONFIRMED`. Se não houver linha, aguarde e repita a consulta, sem criar outro pedido.

## 7. Consulta GET /v1/orders/{pedido_id}

```bash
curl -i "http://localhost:8080/v1/orders/$pedido"
```

Esperado: HTTP 200, mesmo UUID, status `CONFIRMED` e `X-Cache: MISS` na primeira consulta desse pedido. A consulta pública passa pelo Checkout e busca o Processor quando não há cache.

## 8. Uso do Redis na consulta

```text
docker compose exec -T redis redis-cli GET "order:$pedido"
docker compose exec -T redis redis-cli TTL "order:$pedido"
```

Esperado: JSON confirmado e TTL positivo, até 300 segundos. Repita a consulta em menos de cinco minutos:
```bash
curl -i "http://localhost:8080/v1/orders/$pedido"
```

Esperado: `X-Cache: HIT`. Para observar a operação real, em outro terminal execute `docker compose exec -T redis redis-cli MONITOR`, repita o GET e procure a leitura de `order:UUID`; Ctrl+C encerra apenas o monitor. Se o TTL expirou, a primeira consulta será MISS e preencherá o cache novamente.

## 9. Falha controlada no processamento

Registre o marcador **antes** de pausar o Processor. Ele causa cancelamento somente para pedidos cujo `cliente_nome` corresponda ao marcador nesta JVM.

PowerShell:
```powershell
$marcador = 'demo-failure-' + [guid]::NewGuid().ToString()
curl.exe -i -X POST "http://localhost:8081/internal/demo/failures/$marcador"
```

Bash (UUID novo a cada execução, sem ferramenta adicional):
```bash
marcador="demo-failure-$(cat /proc/sys/kernel/random/uuid)"
curl -i -X POST "http://localhost:8081/internal/demo/failures/$marcador"
```

Esperado: HTTP 204. Agora:
```text
docker compose pause order-processor-service
```

PowerShell:
```powershell
@{produto_id='PROD-001'; quantidade=2; cliente_cpf='50329291076'; cliente_nome=$marcador} | ConvertTo-Json -Compress | Set-Content -Encoding ASCII .run/falha.json
curl.exe -sS -D .run/headers-falha.txt -o .run/resposta-falha.json -H 'Content-Type: application/json' --data-binary '@.run/falha.json' http://localhost:8080/v1/orders
Get-Content .run/headers-falha.txt
Get-Content .run/resposta-falha.json
$falha = (Get-Content .run/resposta-falha.json -Raw | ConvertFrom-Json).pedido_id
```

Bash:
```bash
printf '{"produto_id":"PROD-001","quantidade":2,"cliente_cpf":"50329291076","cliente_nome":"%s"}' "$marcador" > .run/falha.json
curl -sS -D .run/headers-falha.txt -o .run/resposta-falha.json -H 'Content-Type: application/json' --data-binary @.run/falha.json http://localhost:8080/v1/orders
cat .run/headers-falha.txt .run/resposta-falha.json
falha='COLE_O_NOVO_UUID_RECEBIDO'
```

Esperado: 202 e outro UUID. Antes de continuar, confira:
```text
docker compose exec -T redis redis-cli GET stock:PROD-001
docker compose exec -T checkout-db psql -U checkout -d checkout -c "SELECT state FROM checkout_outbox WHERE pedido_id='$falha';"
```

Esperado: estoque `6` e outbox `SENT`. **Aguarde SENT antes de pausar o Checkout**, pois ele também publica a outbox.

## 10. Publicação em order-compensation

```text
docker compose pause checkout-service
docker compose unpause order-processor-service
```

Aguarde alguns segundos e consulte:
```text
docker compose logs --since 2m order-processor-service
docker compose exec -T processor-db psql -U processor -d processor -c "SELECT pedido_id, published, payload FROM compensation_outbox WHERE pedido_id='$falha';"
docker compose exec -T rabbitmq rabbitmqctl list_queues -p /flashsale name messages_ready messages_unacknowledged
```

Esperado: outbox `published=t` e mensagem em **order-compensation**. Inspecione seu JSON na interface RabbitMQ com requeue e confira o UUID da falha. O Checkout pausado impede que a mensagem desapareça antes da inspeção. Não faça chamadas HTTP ao Checkout enquanto estiver pausado.

## 11. Devolução do estoque no Redis

```text
docker compose unpause checkout-service
```

Aguarde o consumo e consulte:
```text
docker compose logs --since 2m checkout-service
docker compose exec -T redis redis-cli GET stock:PROD-001
docker compose exec -T redis redis-cli HGETALL "reservation:$falha"
docker compose exec -T checkout-db psql -U checkout -d checkout -c "SELECT pedido_id, compensated FROM checkout_outbox WHERE pedido_id='$falha';"
```

Esperado: estoque volta de `6` para `8`, reserva com `status=COMPLETED` e `compensated=t`. As duas unidades do pedido confirmado continuam consumidas. A fila `order-compensation` deve esvaziar após ack. Se ainda estiver pendente, aguarde e observe os logs; não altere o estoque manualmente.

## 12. Pedido CANCELLED

```text
docker compose exec -T processor-db psql -U processor -d processor -c "SELECT pedido_id, payload::jsonb->>'status' AS status, payload FROM orders WHERE pedido_id='$falha';"
```

```bash
curl -i "http://localhost:8080/v1/orders/$falha"
```

Esperado: linha `CANCELLED` e HTTP 200 com mesmo estado, histórico `PROCESSING → CANCELLED`. O cancelamento já foi persistido na etapa 10, antes da devolução; esta etapa verifica o resultado final. `compensacao=PUBLISHED` no Processor significa publicação, não confirmação de devolução. A evidência da devolução é a etapa 11. Pedidos cancelados não são cacheados.

## Encerramento e diagnóstico

Se interromper o roteiro durante uma pausa, retome o serviço pausado (`docker compose unpause <serviço>`) antes de prosseguir ou encerrar. `docker compose ps -a` mostra o estado. Para repetir, conclua as pendências e volte à etapa 1, usando novos UUIDs.

- HTTP 404 nos controles: confirme inicialização pelo launcher, que habilita `FLASHSALE_DEMO_ENABLED=true`.
- HTTP 409 na compra: saldo insuficiente; confira etapa 1.
- Fila sem mensagem: verifique se pausou o consumidor correto e se a outbox já foi publicada.
- Falha técnica: inspecione logs e filas `order-created.retry`, `order-created.dlq`, `order-compensation.retry`, `order-compensation.dlq`; não equivalem ao cancelamento controlado.
- Portas ocupadas: encerre a outra execução. Se personalizar `.env`, ajuste URLs dos comandos.

## Verificação funcional no MOCK

Inicie as duas JVMs conforme o README. Execute manualmente a preparação HTTP da etapa 1, os POSTs e a leitura de UUID das etapas 2–3, e os GETs das etapas 7–8. Para falha, registre o marcador e envie o segundo POST da etapa 9, **sem os comandos Docker e sem pausas**. Consulte o novo UUID até observar `CANCELLED`.

Inspeções HTTP adicionais (PowerShell: `curl.exe`):
```bash
curl -i http://localhost:8080/internal/demo/stock
curl -i "http://localhost:8080/internal/demo/publications/$pedido"
curl -i "http://localhost:8081/internal/orders/$pedido"
curl -i "http://localhost:8080/internal/demo/reservations/$falha"
```

Esperado: publicação simulada `published=true`, pedido confirmado em memória, reserva cancelada `COMPLETED` e saldo final `8`. Observe os UUIDs nos terminais das JVMs. `X-Cache: HIT` comprova somente cache em memória. Não há filas RabbitMQ nem tabelas PostgreSQL para inspecionar no MOCK; use o REAL para apresentar os 12 requisitos integralmente.
