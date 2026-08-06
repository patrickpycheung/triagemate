package com.company.triage.config;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.cert.X509Certificate;
/**
 * Custom trust strategy to trust all SSL certificates (including corporate proxies with custom CA)
 * on all RestClients configured using the standard RestClient.Builder in this application.
 * Uses standard JDK HttpClient to avoid external dependencies.
 */
@Configuration
public class SslConfiguration {
    public static ClientHttpRequestFactory createTrustAllRequestFactory() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            // Create a standard JDK HttpClient with our trust-all SSLContext
            HttpClient jdkHttpClient = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .build();
            return new JdkClientHttpRequestFactory(jdkHttpClient);
        } catch (Exception e) {
            return new JdkClientHttpRequestFactory();
        }
    }
    @Bean
    public RestClientCustomizer sslRestClientCustomizer() {
        return restClientBuilder -> restClientBuilder.requestFactory(createTrustAllRequestFactory());
    }
}
