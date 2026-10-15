#!/usr/bin/env python3
"""Os mesmos 12 passos para dois processos independentes; somente biblioteca padrão."""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

MODE = sys.argv[1] if len(sys.argv) > 1 else 'real'
CHECKOUT = os.environ.get('CHECKOUT_URL', 'http://127.0.0.1:' + os.environ.get('CHECKOUT_PORT', '8080'))
PROCESSOR = os.environ.get('PROCESSOR_URL', 'http://127.0.0.1:' + os.environ.get('PROCESSOR_PORT', '8081'))
TIMEOUT = float(os.environ.get('DEMO_TIMEOUT', '45'))


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def request(base, path, body=None):
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'})
    try:
        response = urllib.request.urlopen(req, timeout=min(TIMEOUT, 5))
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        body = json.loads(raw) if raw and 'application/json' in response.headers.get('Content-Type', '') else raw.decode()
        return response.status, body, response.headers


def poll(condition):
    deadline = time.monotonic() + TIMEOUT
    while time.monotonic() < deadline:
        result = condition()
        if result:
            return result
        time.sleep(0.1)
    raise AssertionError(f'Timeout de {TIMEOUT}s aguardando estado esperado')


def stock():
    code, body, _ = request(CHECKOUT, '/internal/demo/stock')
    check(code == 200, f'Inspeção de estoque: HTTP {code}; habilite flashsale.demo.enabled nos dois serviços')
    return body['disponivel']


def order(base, path, status):
    code, body, _ = request(base, path)
    check(code in (200, 404), f'Consulta: HTTP {code}: {body}')
    if code == 200:
        check(body['status'] in ('PROCESSING', status), f'Estado inesperado: {body}')
        return body if body['status'] == status else None


def create(name):
    code, body, _ = request(CHECKOUT, '/v1/orders', {'produto_id': 'PROD-001', 'quantidade': 2,
        'cliente_cpf': '50329291076', 'cliente_nome': name})
    check(code == 202, f'POST esperado 202, recebido {code}: {body}')
    check(body['status'] == 'PROCESSING', str(body))
    check(uuid.UUID(body['pedido_id']).version == 4, 'UUID v4 obrigatório')
    return body['pedido_id']


def main():
    check(MODE == 'real', 'Uso: demo-flow.py real')
    for base in (CHECKOUT, PROCESSOR):
        code, health, _ = request(base, '/health/ready')
        check(code == 200 and health['mode'] == MODE, f'{base}: perfil ou infraestrutura incorretos: {health}')
    print('Ambiente dedicado: preparando apenas PROD-001. Não execute com tráfego concorrente.', flush=True)
    code, _, _ = request(CHECKOUT, '/internal/demo/stock', {})
    check(code == 200 and stock() == 10, f'Preparação falhou: HTTP {code}')
    print('01 PASS estoque inicial PROD-001=10')
    success = create('Demo sucesso')
    print('02 PASS POST /v1/orders com dados válidos')
    print('03 PASS HTTP 202, PROCESSING, pedido_id=' + success)
    poll(lambda: request(CHECKOUT, '/internal/demo/publications/' + success)[1].get('published'))
    print('04 PASS publicação confirmada em order-created (' + MODE + ')')
    stored = poll(lambda: order(PROCESSOR, '/internal/orders/' + success, 'CONFIRMED'))
    print('05 PASS consumo no Processor via processo independente')
    check(stored['historico'] == ['PROCESSING', 'CONFIRMED'], str(stored))
    check(stock() == 8, 'Reserva não descontou duas unidades')
    print('06 PASS repositório do Processor: CONFIRMED; estoque=8')
    code, body, headers = request(CHECKOUT, '/v1/orders/' + success)
    check(code == 200 and body['status'] == 'CONFIRMED' and headers.get('X-Cache') == 'MISS', str((code, body, headers)))
    print('07 PASS GET /v1/orders/' + success + ' HTTP 200, cache MISS')
    code, body, headers = request(CHECKOUT, '/v1/orders/' + success)
    check(code == 200 and headers.get('X-Cache') == 'HIT', 'Cache não retornou HIT')
    print('08 PASS segunda consulta: cache HIT; TTL de 300s')
    marker = 'demo-failure-' + str(uuid.uuid4())
    check(request(PROCESSOR, '/internal/demo/failures/' + marker, {})[0] == 204, 'Falha controlada não habilitada')
    failed = create(marker)
    cancelled = poll(lambda: order(PROCESSOR, '/internal/orders/' + failed, 'CANCELLED'))
    print('09 PASS falha controlada, pedido_id=' + failed)
    poll(lambda: request(CHECKOUT, '/internal/demo/reservations/' + failed)[1].get('status') == 'COMPLETED')
    print('10 PASS order-compensation entregue e consumida pelo Checkout')
    check(stock() == 8, 'Compensação não restaurou o estoque para 8')
    print('11 PASS estoque restaurado para 8 (10 menos 2 do pedido confirmado)')
    poll(lambda: order(CHECKOUT, '/v1/orders/' + failed, 'CANCELLED'))
    check(cancelled['historico'] == ['PROCESSING', 'CANCELLED'], str(cancelled))
    print('12 PASS consulta pública: CANCELLED')
    print('PASS roteiro completo. Correlacione os dois UUIDs nos logs dos serviços.')


if __name__ == '__main__':
    try:
        main()
    except (AssertionError, OSError, ValueError, KeyError, urllib.error.URLError) as error:
        print('FAIL:', error, file=sys.stderr)
        sys.exit(1)
