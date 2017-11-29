package com.salesforce.emp.connector;

import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.net.URL;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginAwareBayeuxParameters implements BayeuxParameters {
    private final String username;
    private final String password;
    private final URL loginEndpoint;
    private final BayeuxParameters initialParams;

    private BayeuxParameters params;

    public LoginAwareBayeuxParameters(String username, String password) throws Exception {
        this(new URL(LoginHelper.LOGIN_ENDPOINT), username, password);
    }

    public LoginAwareBayeuxParameters(String username, String password, BayeuxParameters params) throws Exception {
        this(new URL(LoginHelper.LOGIN_ENDPOINT), username, password, params);
    }

    public LoginAwareBayeuxParameters(URL loginEndpoint, String username, String password) throws Exception {
        this(loginEndpoint, username, password, new BayeuxParameters() {
            @Override
            public String bearerToken() {
                throw new IllegalStateException("Have not authenticated");
            }

            @Override
            public URL endpoint() {
                throw new IllegalStateException("Have not established replay endpoint");
            }
        });
    }

    public LoginAwareBayeuxParameters(URL loginEndpoint, String username, String password,
                                         BayeuxParameters initialParams) throws Exception {
        this.loginEndpoint = loginEndpoint;
        this.username = username;
        this.password = password;
        this.initialParams = initialParams;
    }

    public void login() throws Exception {
        params = LoginHelper.login(loginEndpoint, username, password, initialParams);
    }

    @Override
    public String bearerToken() {
        return this.params.bearerToken();
    }

    @Override
    public URL endpoint() {
        return this.params.endpoint();
    }

    @Override
    public URL host() {
        return this.params.host();
    }

    @Override
    public long keepAlive() {
        return this.params.keepAlive();
    }

    @Override
    public TimeUnit keepAliveUnit() {
        return this.params.keepAliveUnit();
    }

    @Override
    public Map<String, Object> longPollingOptions() {
        return this.params.longPollingOptions();
    }

    @Override
    public int maxBufferSize() {
        return this.params.maxBufferSize();
    }

    @Override
    public int maxNetworkDelay() {
        return this.params.maxNetworkDelay();
    }

    @Override
    public Collection<? extends ProxyConfiguration.Proxy> proxies() {
        return this.params.proxies();
    }

    @Override
    public SslContextFactory sslContextFactory() {
        return this.params.sslContextFactory();
    }

    @Override
    public String version() {
        return this.params.version();
    }
}
