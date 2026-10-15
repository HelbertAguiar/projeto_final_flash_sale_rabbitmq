package br.com.estudo.flashsale.application;

import br.com.estudo.flashsale.api.CreateOrderRequest;
import br.com.estudo.flashsale.domain.InvalidOrderException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderValidationTest {
    @Test void reportsAllMissingFieldsIncludingNullBody() {
        for (CreateOrderRequest request : new CreateOrderRequest[] {null,
                new CreateOrderRequest(" ", 0, " ", null)}) {
            var error = assertThrows(InvalidOrderException.class, () -> OrderValidation.validate(request));
            for (String field : new String[] {"produto_id", "quantidade", "cliente_cpf"})
                assertTrue(error.getMessage().contains(field));
        }
    }
    @Test void rejectsNonPositiveOrMissingQuantity() {
        for (Integer quantity : new Integer[] {null, 0, -1})
            assertThrows(InvalidOrderException.class,
                () -> OrderValidation.validate(new CreateOrderRequest("PROD-001", quantity, "123", null)));
    }
    @Test void optionalCustomerNameKeepsOriginalContract() {
        assertDoesNotThrow(() -> OrderValidation.validate(new CreateOrderRequest("PROD-001", 2, "123", null)));
    }
}
