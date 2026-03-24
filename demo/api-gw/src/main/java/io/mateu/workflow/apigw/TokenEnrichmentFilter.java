package io.mateu.workflow.apigw;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;

import java.util.ArrayList;
import java.util.List;

@Component
public class TokenEnrichmentFilter extends AbstractGatewayFilterFactory<TokenEnrichmentFilter.Config> {

    public TokenEnrichmentFilter() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String originalToken = authHeader.substring(7);

                // 1. Lógica para extraer scopes actuales y añadir los nuevos
                String enhancedToken = enrichJwt(originalToken);

                // 2. Mutar la petición con el nuevo token
                ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + enhancedToken)
                        .build();

                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            }

            return chain.filter(exchange);
        };
    }

    private String enrichJwtOld(String token) {
        // Aquí usarías una librería como JJWT o nimbus-jose-jwt
        // - Decodificar el token
        // - Añadir los nuevos claims/scopes
        // - Volver a firmar con una clave que tus microservicios conozcan
        return token + "_enriched"; // Ejemplo simplificado
    }

    private String enrichJwt(String token) {
        try {
            // 1. Parsear el token original
            SignedJWT oldToken = SignedJWT.parse(token);
            JWTClaimsSet oldClaims = oldToken.getJWTClaimsSet();

            // 2. Crear nuevos claims basados en los antiguos + extras
            List<String> scopes = new ArrayList<>(oldClaims.getStringListClaim("scope"));
            scopes.add("CUSTOM_GATEWAY_SCOPE"); // Tu nuevo scope
            scopes.add("APP_ADMIN");

            JWTClaimsSet newClaims = new JWTClaimsSet.Builder(oldClaims)
                    .claim("scope", String.join(" ", scopes)) // O como lista, según tu estándar
                    .issuer("my-api-gateway") // Opcional: cambiar el issuer
                    .build();

            // 3. Firmar el nuevo token
            // NOTA: 'sharedSecret' debe tener al menos 256 bits (32 caracteres)
            JWSSigner signer = new MACSigner("tu_clave_secreta_super_segura_de_32_chars");
            SignedJWT newToken = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), newClaims);
            newToken.sign(signer);

            return newToken.serialize();

        } catch (Exception e) {
            throw new RuntimeException("Error al procesar el JWT", e);
        }
    }

    public static class Config {
        // Parámetros de configuración si los necesitas
    }
}