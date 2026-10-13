package br.com.estudo.flashsale.api;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
@QuarkusTest
class DemoDisabledTest {
    @Test void controlsAreDisabledByDefault() {
        given().contentType("application/json").body("{}").post("/internal/demo/stock").then().statusCode(404);
        given().contentType("application/json").body("{}").post("/internal/demo/failures/demo-failure-00000000-0000-0000-0000-000000000000").then().statusCode(404);
    }
}
