package io.mateu.workflow.apigw;

import com.nimbusds.jose.jwk.RSAKey; // IMPORT CRÍTICO: El de Nimbus, no el de Java
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@Configuration
public class JwtKeyConfig {

    @Value("${app.security.jwt.keystore-path}")
    private Resource keystorePath;

    @Value("${app.security.jwt.keystore-password}")
    private String keystorePassword;

    @Value("${app.security.jwt.key-alias}")
    private String alias;

    @Bean
    public RSAKey rsaKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(keystorePath.getInputStream(), keystorePassword.toCharArray());

        // Extraer clave privada
        RSAPrivateKey privateKey = (RSAPrivateKey) keyStore.getKey(alias, keystorePassword.toCharArray());
        // Extraer clave pública
        RSAPublicKey publicKey = (RSAPublicKey) keyStore.getCertificate(alias).getPublicKey();

        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID("gatekeeper-v1") // ID estático o dinámico
                .build();
    }
}
