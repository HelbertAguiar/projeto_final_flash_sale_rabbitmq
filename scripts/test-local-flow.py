#!/usr/bin/env python3
"""Teste HTTP ponta a ponta. Requer jars construídos com -Dquarkus.profile=test.
Usa somente biblioteca padrão Python, JVMs independentes e portas livres locais.
"""
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
processes = []
logs = []


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


checkout_port, processor_port = free_port(), free_port()
checkout = f'http://127.0.0.1:{checkout_port}'
processor = f'http://127.0.0.1:{processor_port}'


def request(base, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, headers={'Content-Type': 'application/json'})
    try:
        response = urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        if not raw:
            return response.status, None
        if 'application/json' in response.headers.get('Content-Type', ''):
            return response.status, json.loads(raw)
        return response.status, raw.decode(errors='replace')


def await_condition(condition, seconds=20):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            result = condition()
            if result:
                return result
        except (OSError, urllib.error.URLError):
            pass
        threading.Event().wait(0.1)
    raise AssertionError('Condição não concluída dentro do limite')


def start(service, port, *properties):
    log = tempfile.TemporaryFile(mode='w+b')
    logs.append(log)
    proc = subprocess.Popen([JAVA, '-Dquarkus.profile=test', f'-Dquarkus.http.port={port}',
        *properties, '-jar', str(ROOT / service / 'target/quarkus-app/quarkus-run.jar')],
        stdout=log, stderr=subprocess.STDOUT)
    processes.append(proc)
    await_condition(lambda: request(f'http://127.0.0.1:{port}', '/internal/mock/not-found')[0] == 404)
    return proc


class CompensationProxy(BaseHTTPRequestHandler):
    enabled = True

    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.enabled:
            status, _ = request(checkout, self.path, body)
        else:
            status = 503
        self.send_response(status)
        self.end_headers()


def stock():
    return request(checkout, '/internal/mock/stock/PROD-001')[1]['disponivel']


def create():
    status, body = request(checkout, '/v1/orders', {
        'produto_id': 'PROD-001', 'quantidade': 2, 'cliente_cpf': '50329291076',
        'cliente_nome': 'Fernando Augusto'})
    assert status == 202, (status, body)
    assert body['status'] == 'PROCESSING'
    return body['pedido_id']


def order(id, status, compensation):
    code, body = request(processor, '/internal/mock/orders/' + id)
    return body if code == 200 and body['status'] == status and body['compensacao'] == compensation else None


proxy = ThreadingHTTPServer(('127.0.0.1', 0), CompensationProxy)
threading.Thread(target=proxy.serve_forever, daemon=True).start()
try:
    start('checkout-service', checkout_port, '-Dcheckout.events.transport=http',
          f'-Dquarkus.rest-client.processor.url={processor}')
    running = start('order-processor-service', processor_port,
                    f'-Dquarkus.rest-client.checkout.url=http://127.0.0.1:{proxy.server_port}')
    id = create()
    confirmed = await_condition(lambda: order(id, 'CONFIRMED', 'NOT_REQUIRED'))
    assert confirmed['historico'] == ['PROCESSING', 'CONFIRMED']
    assert stock() == 8
    event = {key: confirmed[key] for key in ('pedido_id', 'produto_id', 'quantidade',
                                             'cliente_cpf', 'cliente_nome', 'data_hora_criacao')}
    assert request(processor, '/internal/mock/messages/order-created', event)[0] == 202
    print('PASS: criação HTTP, confirmação assíncrona, reserva e evento duplicado')

    running.terminate()
    running.wait(timeout=15)
    status, _ = request(checkout, '/v1/orders', {
        'produto_id': 'PROD-001', 'quantidade': 2, 'cliente_cpf': '123'})
    assert status == 503 and stock() == 8
    print('PASS: Processor indisponível retorna 503 e devolve reserva')

    start('order-processor-service', processor_port, '-Dprocessor.mock.failure-mode=always',
          f'-Dquarkus.rest-client.checkout.url=http://127.0.0.1:{proxy.server_port}')
    id = create()
    cancelled = await_condition(lambda: order(id, 'CANCELLED', 'COMPLETED'))
    assert cancelled['historico'] == ['PROCESSING', 'CANCELLED'] and stock() == 8
    compensation = {'pedido_id': id, 'produto_id': 'PROD-001', 'quantidade': 2, 'motivo': 'Falha no processamento do pedido'}
    assert request(checkout, '/internal/mock/messages/order-compensation', compensation)[0] == 204
    assert stock() == 8
    print('PASS: cancelamento, compensação HTTP e devolução idempotente')

    CompensationProxy.enabled = False
    id = create()
    await_condition(lambda: order(id, 'CANCELLED', 'PENDING'))
    assert stock() == 6
    retry = f'/internal/mock/orders/{id}/retry-compensation'
    assert request(processor, retry, {})[0] == 503
    CompensationProxy.enabled = True
    assert request(processor, retry, {})[0] == 204
    assert request(processor, retry, {})[0] == 204
    assert order(id, 'CANCELLED', 'COMPLETED') and stock() == 8
    print('PASS: falha de entrega mantém pendência; retry recupera sem devolução duplicada')
except BaseException:
    for log in logs:
        log.seek(0)
        print(log.read().decode(errors='replace'))
    raise
finally:
    for proc in processes:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
    proxy.shutdown()
    proxy.server_close()
    for log in logs:
        log.close()
