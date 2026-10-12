package br.com.estudo.flashsale.infrastructure;

import br.com.estudo.flashsale.domain.OrderCompensationEvent;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
@RegisterRestClient(configKey="checkout")
@Path("/internal/mock/messages/order-compensation") @Consumes("application/json")
public interface CheckoutClient { @POST Response send(OrderCompensationEvent event); }
