# Flash Sale — etapa 4.1: Checkout

Este repositório contém o MVP do microsserviço de Checkout descrito em [Projeto_final.md](./Projeto_final.md). Nesta etapa, apenas `POST /v1/orders` está implementado. O serviço recebe o pedido, reserva estoque em memória e registra um evento em uma fila lógica `order-created` também em memória.

Consulte [checkout-service/README.md](./checkout-service/README.md) para arquitetura, execução, exemplos de `curl`, testes e limitações.

O arquivo [compose.yml](./compose.yml) antecipa a infraestrutura de etapas futuras; **não é necessário** para executar este MVP.
