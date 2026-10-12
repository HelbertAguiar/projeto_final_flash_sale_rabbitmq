package br.com.estudo.flashsale.infrastructure;

import com.rabbitmq.client.*;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.*;
import java.util.function.Consumer;

/**
 * Um canal serializado por processo. Polling em lotes evita bloquear a thread HTTP.
 * Publicação durável com confirms; confirmação do consumo somente após o handler.
 */
@ApplicationScoped
public class RabbitBroker {
    @ConfigProperty(name="rabbitmq.host") String host;
    @ConfigProperty(name="rabbitmq.port") int port;
    @ConfigProperty(name="rabbitmq.username") String username;
    @ConfigProperty(name="rabbitmq.password") String password;
    @ConfigProperty(name="rabbitmq.virtual-host") String virtualHost;
    private Connection connection;
    private Channel channel;
    public static final List<String> QUEUES = List.of("order-created", "order-compensation");

    private void connect() throws Exception {
        if (channel != null && channel.isOpen()) return;
        close();
        var factory = new ConnectionFactory();
        factory.setHost(host); factory.setPort(port);
        factory.setUsername(username); factory.setPassword(password);
        factory.setVirtualHost(virtualHost);
        factory.setConnectionTimeout(3000); factory.setHandshakeTimeout(3000);
        factory.setRequestedHeartbeat(10);
        factory.setAutomaticRecoveryEnabled(false); // Nova conexão no próximo ciclo do worker.
        connection = factory.newConnection();
        channel = connection.createChannel();
        channel.confirmSelect();
        for (String queue : QUEUES) {
            channel.queueDeclare(queue + ".dlq", true, false, false, Map.of());
            channel.queueDeclare(queue, true, false, false, Map.of(
                "x-dead-letter-exchange", "", "x-dead-letter-routing-key", queue + ".dlq"));
            channel.queueDeclare(queue + ".retry", true, false, false, Map.of(
                "x-message-ttl", 5000, "x-dead-letter-exchange", "",
                "x-dead-letter-routing-key", queue));
        }
    }

    public synchronized void publish(String queue, byte[] body) {
        try {
            connect();
            send(queue, body, Map.of());
        } catch (Exception error) {
            close();
            throw new IllegalStateException("Publicação RabbitMQ pendente", error);
        }
    }

    private void send(String queue, byte[] body, Map<String,Object> headers) throws Exception {
        var returned = new java.util.concurrent.atomic.AtomicBoolean();
        ReturnListener listener = (code, text, exchange, key, properties, bytes) -> returned.set(true);
        channel.addReturnListener(listener);
        try {
            channel.basicPublish("", queue, true, new AMQP.BasicProperties.Builder()
                .contentType("application/json").deliveryMode(2).headers(headers).build(), body);
            channel.waitForConfirmsOrDie(3000);
            if (returned.get()) throw new IllegalStateException("Mensagem sem rota");
        } finally { channel.removeReturnListener(listener); }
    }

    public synchronized void consume(String queue, Consumer<byte[]> handler) {
        try {
            connect();
            for (int i = 0; i < 20; i++) {
                var delivery = channel.basicGet(queue, false);
                if (delivery == null) return;
                long tag = delivery.getEnvelope().getDeliveryTag();
                try {
                    handler.accept(delivery.getBody());
                } catch (RuntimeException failure) {
                    var headers = new HashMap<String,Object>();
                    if (delivery.getProps().getHeaders() != null)
                        headers.putAll(delivery.getProps().getHeaders());
                    int attempts = ((Number) headers.getOrDefault("x-attempt", 0)).intValue() + 1;
                    headers.put("x-attempt", attempts);
                    // Original só recebe ack depois de confirmar a cópia durável.
                    send(attempts < 3 ? queue + ".retry" : queue + ".dlq", delivery.getBody(), headers);
                    java.util.logging.Logger.getLogger(getClass().getName()).warning(
                        "Falha em " + queue + "; tentativa=" + attempts);
                }
                channel.basicAck(tag, false);
            }
        } catch (Exception error) {
            close(); // Entregas sem ack voltam à fila.
            throw new IllegalStateException("Consumo RabbitMQ indisponível", error);
        }
    }

    public synchronized boolean ready() {
        try { connect(); return true; }
        catch (Exception error) { close(); return false; }
    }

    @PreDestroy
    public synchronized void close() {
        try { if (connection != null) connection.close(); } catch (Exception ignored) { }
        channel = null; connection = null;
    }
}

