package br.com.estudo.flashsale.domain;

public class PublicationFailedException extends RuntimeException {
    public PublicationFailedException(Throwable cause) {
        super("Não foi possível publicar o pedido", cause);
    }
}
