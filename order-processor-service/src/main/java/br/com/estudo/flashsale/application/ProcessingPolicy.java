package br.com.estudo.flashsale.application;

import br.com.estudo.flashsale.domain.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Decisão de negócio; falhas configuráveis e marcadores para demonstrações. */
@ApplicationScoped
public class ProcessingPolicy {
    private final String failureMode;
    private final Set<String> failures = ConcurrentHashMap.newKeySet();
    @Inject
    public ProcessingPolicy(@ConfigProperty(name="processor.failure-mode", defaultValue="never") String mode) {
        if (!Set.of("never", "always").contains(mode)) throw new IllegalArgumentException("failure-mode inválido");
        failureMode = mode;
    }
    public void failMarker(String marker) { failures.add(marker); }
    public OrderStatus result(OrderCreatedEvent event) {
        return failureMode.equals("always") || (event.clienteNome() != null && failures.contains(event.clienteNome()))
            ? OrderStatus.CANCELLED : OrderStatus.CONFIRMED;
    }
}
