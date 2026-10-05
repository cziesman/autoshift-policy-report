package com.redhat.autoshift.report.repository;

import java.net.Proxy;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;

/**
 * Disables TLS certificate and hostname verification for a single JGit HTTP
 * transport. This does not modify the JVM's default SSL configuration.
 */
final class GitTransportConfigCallback implements TransportConfigCallback {

    private static final TrustManager[] TRUST_ALL_CERTIFICATES = {
            new X509TrustManager() {
                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    // Trust all certificates for this JGit transport.
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    // Trust all certificates for this JGit transport.
                }
            }
    };

    private static final HostnameVerifier TRUST_ALL_HOSTNAMES = (hostname, session) -> true;

    @Override
    public void configure(Transport transport) {
        if (transport instanceof TransportHttp httpTransport) {
            httpTransport.setHttpConnectionFactory(new InsecureHttpConnectionFactory());
        }
    }

    private static final class InsecureHttpConnectionFactory extends JDKHttpConnectionFactory {

        @Override
        public HttpConnection create(URL url) throws java.io.IOException {
            return configure(super.create(url));
        }

        @Override
        public HttpConnection create(URL url, Proxy proxy) throws java.io.IOException {
            return configure(super.create(url, proxy));
        }

        private HttpConnection configure(HttpConnection connection) throws java.io.IOException {
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                return connection;
            }

            try {
                connection.configure(null, TRUST_ALL_CERTIFICATES, null);
                connection.setHostnameVerifier(TRUST_ALL_HOSTNAMES);
                return connection;
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new java.io.IOException("Unable to disable TLS certificate verification for JGit", e);
            }
        }
    }
}
