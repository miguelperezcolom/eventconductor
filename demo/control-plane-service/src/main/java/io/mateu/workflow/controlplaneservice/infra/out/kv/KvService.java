package io.mateu.workflow.controlplaneservice.infra.out.kv;

import org.springframework.stereotype.Service;

@Service
public class KvService {

    private final CloudflareKvClient client;

    public KvService(CloudflareKvClient client) {
        this.client = client;
    }

    public void ejemplo() {
        client.putValue("feature_flag", "true");
        client.putValue("temp_key", "valor");
    }

    public void ejemplo2(String key) {
        String value = client.getValue("feature_flag");

        if (value != null) {
            System.out.println("Valor: " + value);
        } else {
            System.out.println("No existe la clave");
        }
    }
}