package br.com.estudo.flashsale.ports;

public interface StockRepository {
    int available(String produtoId);

    boolean reserve(String produtoId, int quantidade);

    void restore(String produtoId, int quantidade);
}
