package br.com.estudo.flashsale.ports;
import java.util.Optional;
public interface OrderCache {
    Optional<String> get(String key);
    void put(String key, String value);
}
