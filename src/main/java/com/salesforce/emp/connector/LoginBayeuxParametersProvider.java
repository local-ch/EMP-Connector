/*
 * Copyright (c) 2016, salesforce.com, inc. All rights reserved. Licensed under the BSD 3-Clause license. For full
 * license text, see LICENSE.TXT file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.emp.connector;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.URL;
import java.nio.ByteBuffer;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A helper to obtain the Authentication bearer token via login
 *
 * @author hal.hildebrand
 * @since 202
 */
public class LoginBayeuxParametersProvider implements BayeuxParametersProvider {

    private static class LoginResponseParser extends DefaultHandler {

        private String buffer;
        private String faultstring;

        private boolean reading = false;
        private String serverUrl;
        private String sessionId;

        @Override
        public void characters(char[] ch, int start, int length) {
            if (reading) buffer = new String(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            reading = false;
            switch (localName) {
            case "sessionId":
                sessionId = buffer;
                break;
            case "serverUrl":
                serverUrl = buffer;
                break;
            case "faultstring":
                faultstring = buffer;
                break;
            default:
            }
            buffer = null;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            switch (localName) {
            case "sessionId":
                reading = true;
                break;
            case "serverUrl":
                reading = true;
                break;
            case "faultstring":
                reading = true;
                break;
            default:
            }
        }
    }

    public static final String COMETD_REPLAY = "/cometd/";
    public static final String COMETD_REPLAY_OLD = "/cometd/replay/";
    static final String LOGIN_ENDPOINT = "https://login.salesforce.com";
    private static final String ENV_END = "</soapenv:Body></soapenv:Envelope>";
    private static final String ENV_START = "<soapenv:Envelope xmlns:soapenv='http://schemas.xmlsoap.org/soap/envelope/' "
            + "xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' "
            + "xmlns:urn='urn:partner.soap.sforce.com'><soapenv:Body>";

    // The enterprise SOAP API endpoint used for the login call
    private static final String SERVICES_SOAP_PARTNER_ENDPOINT = "/services/Soap/u/22.0/";

    private final URL loginEndpoint;
    private final String username;
    private final String password;
    private final BayeuxParameters initialParams;

    private BayeuxParameters params;

    private static final BayeuxParameters DEFAULT_PARAMS = new BayeuxParameters() {
        @Override
        public String bearerToken() {
            throw new IllegalStateException("Have not authenticated");
        }

        @Override
        public URL endpoint() {
            throw new IllegalStateException("Have not established replay endpoint");
        }
    };

    public LoginBayeuxParametersProvider(String username, String password) throws Exception {
        this(new URL(LOGIN_ENDPOINT), username, password);
    }

    public LoginBayeuxParametersProvider(String username, String password, BayeuxParameters params) throws Exception {
        this(new URL(LOGIN_ENDPOINT), username, password, params);
    }

    public LoginBayeuxParametersProvider(URL loginEndpoint, String username, String password) throws Exception {
        this(loginEndpoint, username, password, DEFAULT_PARAMS);
    }

    public LoginBayeuxParametersProvider(URL loginEndpoint, String username, String password, BayeuxParameters initialParams) {
        this.loginEndpoint = loginEndpoint;
        this.username = username;
        this.password = password;
        this.initialParams = initialParams;
    }

    public void login() throws Exception {
        HttpClient client = new HttpClient(params.sslContextFactory());
        client.getProxyConfiguration().getProxies().addAll(params.proxies());
        client.start();
        URL endpoint = new URL(loginEndpoint, getSoapUri());
        Request post = client.POST(endpoint.toURI());
        post.content(new ByteBufferContentProvider("text/xml", ByteBuffer.wrap(soapXmlForLogin(username, password))));
        post.header("SOAPAction", "''");
        post.header("PrettyPrint", "Yes");
        ContentResponse response = post.send();
        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        spf.setNamespaceAware(true);
        SAXParser saxParser = spf.newSAXParser();

        LoginResponseParser parser = new LoginResponseParser();
        saxParser.parse(new ByteArrayInputStream(response.getContent()), parser);

        String sessionId = parser.sessionId;
        if (sessionId == null || parser.serverUrl == null) { throw new ConnectException(
                String.format("Unable to login: %s", parser.faultstring)); }

        URL soapEndpoint = new URL(parser.serverUrl);
        String cometdEndpoint = Float.parseFloat(params.version()) < 37 ? COMETD_REPLAY_OLD : COMETD_REPLAY;
        URL replayEndpoint = new URL(soapEndpoint.getProtocol(), soapEndpoint.getHost(), soapEndpoint.getPort(),
                new StringBuilder().append(cometdEndpoint).append(params.version()).toString());

        this.params = new DelegatingBayeuxParameters(params) {
            @Override
            public String bearerToken() {
                return sessionId;
            }

            @Override
            public URL endpoint() {
                return replayEndpoint;
            }
        };
    }

    private static String getSoapUri() {
        return SERVICES_SOAP_PARTNER_ENDPOINT;
    }

    private static byte[] soapXmlForLogin(String username, String password) throws UnsupportedEncodingException {
        return (ENV_START + "  <urn:login>" + "    <urn:username>" + username + "</urn:username>" + "    <urn:password>"
                + password + "</urn:password>" + "  </urn:login>" + ENV_END).getBytes("UTF-8");
    }

    @Override
    public BayeuxParameters params() {
        return this.params;
    }
}
