package br.com.estudo.flashsale.domain;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String produtoId) {
        super("Estoque insuficiente para o produto " + produtoId);
    }
}
