package br.com.estudo.flashsale.ports;

import br.com.estudo.flashsale.domain.OrderCompensationEvent;
public interface OrderCompensationPublisher { void publish(OrderCompensationEvent event); }
