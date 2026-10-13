package br.com.estudo.flashsale.application;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.locks.ReentrantReadWriteLock;
/** Permite compras concorrentes; a preparação exclusiva aguarda todos os POSTs. */
@ApplicationScoped
public class DemoMaintenance {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    public java.util.concurrent.locks.Lock requests() { return lock.readLock(); }
    public java.util.concurrent.locks.Lock preparation() { return lock.writeLock(); }
}
