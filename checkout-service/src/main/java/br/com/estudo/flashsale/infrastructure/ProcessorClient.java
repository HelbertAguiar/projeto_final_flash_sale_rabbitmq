package br.com.estudo.flashsale.infrastructure;

import br.com.estudo.flashsale.domain.OrderCreatedEvent;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
@RegisterRestClient(configKey="processor")
@Path("/internal/mock/messages/order-created") @Consumes("application/json")
public interface ProcessorClient { @POST Response send(OrderCreatedEvent event); }
