package io.mateu.workflow.apigw;

import com.github.benmanes.caffeine.cache.Cache;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TokenEnrichmentFilter extends AbstractGatewayFilterFactory<TokenEnrichmentFilter.Config> {

    @GrpcClient("auth-service")
    private AuthServiceGrpc.AuthServiceBlockingStub authServiceStub;

    // Inyectamos el Bean de configuración que lee el Keystore
    private final RSAKey rsaKey;
    private final Cache<String, GetAuthInfoReply> authCache; // Inyectamos la caché

    public TokenEnrichmentFilter(RSAKey rsaKey, Cache<String, GetAuthInfoReply> authCache) {
        super(Config.class);
        this.rsaKey = rsaKey;
        this.authCache = authCache;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String originalToken = authHeader.substring(7);

                return Mono.fromCallable(() -> enrichJwt(originalToken))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(enhancedToken -> {
                            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + enhancedToken)
                                    .header("X-Token-Before-Auth", "Bearer " + originalToken)
                                    .build();
                            return chain.filter(exchange.mutate().request(mutatedRequest).build());
                        })
                        .onErrorResume(e -> {
                            // Si algo falla (gRPC caído, token corrupto), logueamos y dejamos pasar el original
                            // O podrías devolver un 401 aquí.
                            return chain.filter(exchange);
                        });
            }
            return chain.filter(exchange);
        };
    }

    private Mono<GetAuthInfoReply> getAuthInfoWithCache(String username) {
        // Intentamos recuperar de la caché
        GetAuthInfoReply cached = authCache.getIfPresent(username);

        if (cached != null) {
            return Mono.just(cached);
        }

        // Si no está, llamamos a gRPC en un hilo elástico y guardamos el resultado
        return Mono.fromCallable(() -> {
            GetAuthInfoReply reply = authServiceStub.getAuthInfo(
                    GetAuthInfoRequest.newBuilder().setUser(username).build()
            );
            authCache.put(username, reply); // Guardamos en caché para la próxima
            return reply;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String enrichJwt(String token) throws Exception {
        SignedJWT oldToken = SignedJWT.parse(token);
        JWTClaimsSet oldClaims = oldToken.getJWTClaimsSet();

        // Obtenemos el usuario (asegúrate de que este claim existe en tu token de Keycloak)
        String username = oldClaims.getClaimAsString("preferred_username");
        if (username == null) username = oldClaims.getSubject();

        // Bloqueamos brevemente el flujo interno del Callable para obtener el Reply
        // (Como ya estamos dentro de un Schedulers.boundedElastic, esto es seguro)
        GetAuthInfoReply authInfo = getAuthInfoWithCache(username).block();

        // Procesar Roles para realm_access.roles
        List<String> newRoles = Arrays.stream(authInfo.getRoles().split(" "))
                .filter(r -> !r.isBlank())
                .collect(Collectors.toList());

        Map<String, Object> realmAccess = new HashMap<>();
        realmAccess.put("roles", newRoles);

        // Construir nuevos claims manteniendo los anteriores
        JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder(oldClaims);

        claimsBuilder.claim("realm_access", realmAccess);
        claimsBuilder.claim("scope", authInfo.getScopes());
        claimsBuilder.issuer("my-api-gateway"); // Identificamos que el Gateway lo modificó

        return signNewToken(claimsBuilder.build());
    }

    private String signNewToken(JWTClaimsSet newClaims) throws JOSEException {
        // Usamos RS256 con la clave privada del Keystore
        JWSSigner signer = new RSASSASigner(rsaKey.toRSAPrivateKey());

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rsaKey.getKeyID()) // Importante para que el receptor sepa qué clave usar
                .build();

        SignedJWT newToken = new SignedJWT(header, newClaims);
        newToken.sign(signer);

        return newToken.serialize();
    }

    public static class Config { }
}