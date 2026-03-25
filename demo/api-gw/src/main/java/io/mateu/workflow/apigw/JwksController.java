package io.mateu.workflow.apigw;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class JwksController {

    private final com.nimbusds.jose.jwk.RSAKey rsaKey;

    public JwksController(com.nimbusds.jose.jwk.RSAKey rsaKey) {
        this.rsaKey = rsaKey;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> getJwks() {
        // Importante: .toPublicJWK() para no filtrar la clave privada
        return new com.nimbusds.jose.jwk.JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }
}