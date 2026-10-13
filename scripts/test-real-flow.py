#!/usr/bin/env python3
"""Integração real, biblioteca padrão. Cria projeto Compose isolado e remove só seus recursos.

Requer Docker/Compose e acesso ao daemon. Não usa o .env do usuário.
"""
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import socket
import sys
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
PROJECT = 'flashsale-it-' + uuid.uuid4().hex[:10]
ENV = os.environ.copy()
ENV.update(RABBITMQ_USERNAME='flashsale', RABBITMQ_PASSWORD='flashsale',
           CHECKOUT_DB_PASSWORD='checkout', PROCESSOR_DB_PASSWORD='processor',
           PROCESSOR_FAILURE_MODE='never', FLASHSALE_DEMO_ENABLED='true')
# Reserva portas distintas durante a escolha; o Docker passa a ocupá-las ao subir.
port_sockets = []
for key in ('CHECKOUT_PORT', 'PROCESSOR_PORT', 'REDIS_PORT', 'RABBITMQ_PORT',
            'RABBITMQ_MANAGEMENT_PORT', 'CHECKOUT_DB_PORT', 'PROCESSOR_DB_PORT'):
    sock = socket.socket()
    sock.bind(('127.0.0.1', 0))
    ENV[key] = str(sock.getsockname()[1])
    port_sockets.append(sock)
for sock in port_sockets:
    sock.close()
COMPOSE = ['docker', 'compose', '--env-file', '/dev/null', '-p', PROJECT, '-f', str(ROOT / 'compose.yml')]
BASE = 'http://127.0.0.1:' + ENV['CHECKOUT_PORT']
PROCESSOR = 'http://127.0.0.1:' + ENV['PROCESSOR_PORT']
MANAGEMENT = 'http://127.0.0.1:' + ENV['RABBITMQ_MANAGEMENT_PORT']


def compose(*args, timeout=180):
    result = subprocess.run(COMPOSE + list(args), cwd=ROOT, env=ENV, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stdout)
    return result.stdout.strip()


def request(base, path, body=None, auth=False):
    headers = {'Content-Type': 'application/json'}
    if auth:
        headers['Authorization'] = 'Basic ' + base64.b64encode(b'flashsale:flashsale').decode()
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else None


def eventually(condition, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            result = condition()
            if result:
                return result
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.25)
    raise AssertionError('Condição não atingida no prazo')


def ready():
    eventually(lambda: request(BASE, '/health/ready')[0] == 200)
    eventually(lambda: request(PROCESSOR, '/health/ready')[0] == 200)


def redis(*args):
    return compose('exec', '-T', 'redis', 'redis-cli', '--raw', *args)


def sql(service, user, query):
    return compose('exec', '-T', service, 'psql', '-U', user, '-d', user, '-Atc', query)


def create(product, quantity=1):
    return request(BASE, '/v1/orders', {'produto_id': product, 'quantidade': quantity,
                                      'cliente_cpf': '12345678909', 'cliente_nome': 'Teste'})


def accepted(product, quantity=1):
    code, body = create(product, quantity)
    assert code == 202, (code, body)
    return body['pedido_id']


def terminal(id, state):
    code, body = request(BASE, '/v1/orders/' + id)
    return body if code == 200 and body['status'] == state else None


def publish(queue, event):
    code, result = request(MANAGEMENT, '/api/exchanges/%2Fflashsale/amq.default/publish', {
        'properties': {'delivery_mode': 2, 'content_type': 'application/json'},
        'routing_key': queue, 'payload': json.dumps(event), 'payload_encoding': 'string'}, auth=True)
    assert code == 200 and result['routed'], (code, result)


try:
    print('Construindo e iniciando projeto isolado:', PROJECT, flush=True)
    compose('up', '-d', '--build', '--wait', '--wait-timeout', '180', timeout=900)
    ready()
    subprocess.run([sys.executable, str(ROOT / 'scripts/demo-flow.py'), 'real'],
                   env=dict(ENV, CHECKOUT_URL=BASE, PROCESSOR_URL=PROCESSOR), check=True)
    product = 'IT-' + uuid.uuid4().hex
    redis('SET', 'stock:' + product, '10')
    assert create(product, 0)[0] == 400
    assert request(BASE, '/v1/orders/' + str(uuid.uuid4()))[0] == 404
    id = accepted(product, 2)
    confirmed = eventually(lambda: terminal(id, 'CONFIRMED'))
    assert redis('GET', 'stock:' + product) == '8'
    eventually(lambda: sql('checkout-db', 'checkout', f"SELECT state FROM checkout_outbox WHERE pedido_id='{id}'") == 'SENT')
    assert sql('processor-db', 'processor', f"SELECT count(*) FROM orders WHERE pedido_id='{id}'") == '1'
    assert 0 < int(redis('TTL', 'order:' + id)) <= 300
    print('PASS: contrato, confirmação, bancos separados e cache TTL', flush=True)

    # Duplicidade real pelo broker não cria um segundo pedido.
    event = {key: confirmed[key] for key in ('pedido_id', 'produto_id', 'quantidade',
                                            'cliente_cpf', 'cliente_nome', 'data_hora_criacao')}
    publish('order-created', event)
    compose('restart', 'redis', 'rabbitmq', 'checkout-db', 'processor-db',
            'checkout-service', 'order-processor-service')
    ready()
    redis('DEL', 'order:' + id)
    assert eventually(lambda: terminal(id, 'CONFIRMED'))
    assert redis('GET', 'stock:' + product) == '8'
    assert sql('processor-db', 'processor', f"SELECT count(*) FROM orders WHERE pedido_id='{id}'") == '1'
    print('PASS: duplicidade e persistência após reiniciar aplicações e dependências', flush=True)

    compose('stop', 'rabbitmq')
    pending = accepted(product)
    assert sql('checkout-db', 'checkout', f"SELECT state FROM checkout_outbox WHERE pedido_id='{pending}'") == 'READY'
    assert request(BASE, '/v1/orders/' + pending)[1]['status'] == 'PROCESSING'
    compose('restart', 'checkout-service')
    compose('start', 'rabbitmq')
    ready()
    eventually(lambda: terminal(pending, 'CONFIRMED'))
    assert redis('GET', 'stock:' + product) == '7'
    print('PASS: outbox recupera publicação após falha do broker e reinício do Checkout', flush=True)

    compose('stop', 'redis')
    code, pending_body = create(product)
    assert code == 503 and 'pedido_id' in pending_body, (code, pending_body)
    pending_reservation = pending_body['pedido_id']
    assert sql('checkout-db', 'checkout', f"SELECT state FROM checkout_outbox WHERE pedido_id='{pending_reservation}'") == 'PREPARED'
    compose('start', 'redis')
    ready()
    eventually(lambda: terminal(pending_reservation, 'CONFIRMED'))
    assert redis('GET', 'stock:' + product) == '6'
    print('PASS: intenção de reserva recuperada após indisponibilidade do Redis', flush=True)

    concurrent_product = product + '-CONCURRENT'
    redis('SET', 'stock:' + concurrent_product, '5')
    with ThreadPoolExecutor(max_workers=12) as executor:
        results = list(executor.map(lambda _: create(concurrent_product), range(15)))
    assert sum(code == 202 for code, _ in results) == 5, results
    assert sum(code == 409 for code, _ in results) == 10, results
    assert redis('GET', 'stock:' + concurrent_product) == '0'
    for code, body in results:
        if code == 202:
            eventually(lambda: terminal(body['pedido_id'], 'CONFIRMED'))
    print('PASS: concorrência sem overselling', flush=True)

    ENV['PROCESSOR_FAILURE_MODE'] = 'always'
    compose('up', '-d', '--no-deps', '--force-recreate', 'order-processor-service')
    ready()
    cancelled = accepted(product, 2)
    eventually(lambda: terminal(cancelled, 'CANCELLED'))
    eventually(lambda: redis('HGET', 'reservation:' + cancelled, 'status') == 'COMPLETED')
    eventually(lambda: sql('checkout-db', 'checkout', f"SELECT compensated FROM checkout_outbox WHERE pedido_id='{cancelled}'") == 't')
    assert redis('GET', 'stock:' + product) == '6'
    compose('restart', 'checkout-service')
    ready()
    for _ in range(5):
        publish('order-compensation', {'pedido_id': cancelled, 'produto_id': product,
                                      'quantidade': 2, 'motivo': 'Reentrega'})
    sentinel = accepted(product)
    eventually(lambda: redis('HGET', 'reservation:' + sentinel, 'status') == 'COMPLETED')
    assert redis('GET', 'stock:' + product) == '6'
    print('PASS: cancelamento e compensação idempotente após reinício', flush=True)

    publish('order-created', {'invalido': True})
    eventually(lambda: int(compose('exec', '-T', 'rabbitmq', 'rabbitmqctl', '-q', 'list_queues',
                                  '-p', '/flashsale', 'name', 'messages').split('order-created.dlq\t')[1].splitlines()[0]) >= 1)
    print('PASS: mensagem inválida segue para DLQ após tentativas limitadas', flush=True)
    trace = compose('logs', 'checkout-service', 'order-processor-service')
    for expected in ('event=published queue=order-created pedido_id=',
                     'event=consumed queue=order-created pedido_id=',
                     'event=published queue=order-compensation pedido_id=',
                     'event=compensated queue=order-compensation pedido_id='):
        assert expected in trace, expected
    print('PASS: logs reais correlacionados de publicação, consumo e compensação', flush=True)
except BaseException:
    print(compose('logs', '--tail=80', 'checkout-service', 'order-processor-service'))
    raise
finally:
    # Projeto único criado exclusivamente por esta execução; não toca o ambiente normal.
    compose('down', '--volumes', '--remove-orphans')
