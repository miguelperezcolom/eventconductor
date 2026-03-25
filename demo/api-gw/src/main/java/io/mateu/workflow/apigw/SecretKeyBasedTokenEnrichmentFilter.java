package io.mateu.workflow.apigw;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.mateu.demo.lib.AuthServiceGrpc;
import io.mateu.demo.lib.GetAuthInfoReply;
import io.mateu.demo.lib.GetAuthInfoRequest;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class SecretKeyBasedTokenEnrichmentFilter extends AbstractGatewayFilterFactory<SecretKeyBasedTokenEnrichmentFilter.Config> {

    // 1. Inyectar el cliente gRPC (Spring se encarga del ciclo de vida del canal)
    @GrpcClient("auth-service")
    private AuthServiceGrpc.AuthServiceBlockingStub authServiceStub;

    public SecretKeyBasedTokenEnrichmentFilter() {
        super(SecretKeyBasedTokenEnrichmentFilter.Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String originalToken = authHeader.substring(7);

                // 2. Ejecutar de forma no bloqueante
                return Mono.fromCallable(() -> enrichJwt(originalToken))
                        .subscribeOn(Schedulers.boundedElastic()) // No bloqueamos el Event Loop
                        .flatMap(enhancedToken -> {
                            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + enhancedToken)
                                    .header("X-Token-Before-Auth", "Bearer " + originalToken)
                                    .build();
                            return chain.filter(exchange.mutate().request(mutatedRequest).build());
                        });
            }
            return chain.filter(exchange);
        };
    }

    private String enrichJwt(String token) throws Exception {
        SignedJWT oldToken = SignedJWT.parse(token);
        JWTClaimsSet oldClaims = oldToken.getJWTClaimsSet();
        String username = oldClaims.getClaimAsString("preferred_username"); //oldClaims.getSubject();

        // 3. Usamos el stub inyectado (ya no creamos canales)
        GetAuthInfoReply authInfo = authServiceStub.getAuthInfo(
                GetAuthInfoRequest.newBuilder().setUser(username).build()
        );

        // 1. Convertimos el String de roles (ej: "admin user") en una List<String>
        List<String> newRoles = Arrays.asList(authInfo.getRoles().split(" "));

        // 2. Creamos la estructura jerárquica: realm_access -> roles -> [lista]
        Map<String, Object> realmAccess = new HashMap<>();
        realmAccess.put("roles", newRoles);

        // 3. Construimos los nuevos claims
        JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder(oldClaims);

        // Añadimos/Sobrescribimos el claim estructurado
        claimsBuilder.claim("realm_access", realmAccess);

        // Los scopes suelen ir en el nivel raíz como un String separado por espacios
        claimsBuilder.claim("scope", authInfo.getScopes());

        claimsBuilder.issuer("my-api-gateway");

        JWTClaimsSet newClaims = claimsBuilder.build();

        // ... lógica de firmado (idealmente cargando la clave desde Config)
        return signNewToken(newClaims);
    }

    private String signNewToken(JWTClaimsSet newClaims) throws JOSEException {
        // 3. Firmar el nuevo token
        // NOTA: 'sharedSecret' debe tener al menos 256 bits (32 caracteres)
        JWSSigner signer = new MACSigner("tu_clave_secreta_super_segura_de_32_chars");
        SignedJWT newToken = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), newClaims);
        newToken.sign(signer);

        return newToken.serialize();
    }

    public static class Config {
        // Parámetros de configuración si los necesitas
    }
}
