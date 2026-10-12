package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.domain.InsufficientStockException;
import br.com.estudo.flashsale.domain.InvalidOrderException;
import br.com.estudo.flashsale.domain.PublicationFailedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class CheckoutExceptionMapper implements ExceptionMapper<RuntimeException> {
    @Override
    public Response toResponse(RuntimeException exception) {
        int status;
        String message;
        if (exception instanceof InvalidOrderException) {
            status = 400;
            message = exception.getMessage();
        } else if (exception instanceof InsufficientStockException) {
            status = 409;
            message = exception.getMessage();
        } else if (exception instanceof PublicationFailedException) {
            status = 503;
            message = exception.getMessage();
        } else if (exception instanceof jakarta.ws.rs.WebApplicationException httpError) {
            status = httpError.getResponse().getStatus();
            message = "Requisição HTTP rejeitada";
        } else {
            status = 500;
            message = "Erro interno ao criar pedido";
        }
        return Response.status(status).entity(new ErrorResponse(message)).build();
    }
}
