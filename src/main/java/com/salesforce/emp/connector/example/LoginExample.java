/* 
 * Copyright (c) 2016, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license. 
 * For full license text, see LICENSE.TXT file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.emp.connector.example;


import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.salesforce.emp.connector.BayeuxParameters;
import com.salesforce.emp.connector.EmpConnector;
import com.salesforce.emp.connector.LoginBayeuxParametersProvider;
import com.salesforce.emp.connector.TopicSubscription;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;

/**
 * An example of using the EMP connector using login credentials
 *
 * @author hal.hildebrand
 * @since 202
 */
public class LoginExample {
    public static void main(String[] argv) throws Exception {
        if (argv.length < 3 || argv.length > 4) {
            System.err.println("Usage: LoginExample username password topic [replayFrom]");
            System.exit(1);
        }
        long replayFrom = EmpConnector.REPLAY_FROM_EARLIEST;
        if (argv.length == 4) {
            replayFrom = Long.parseLong(argv[3]);
        }

        LoginBayeuxParametersProvider loginParamsProvider = new LoginBayeuxParametersProvider(argv[0], argv[1]);
        try {
            loginParamsProvider.login();
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
            throw e;
        } 

        Consumer<Map<String, Object>> consumer = event -> System.out.println(String.format("Received:\n%s", event));
        EmpConnector connector = new EmpConnector(loginParamsProvider);
        connector.addListener(Channel.META_HANDSHAKE, (channel, message) -> {
            if (!message.isSuccessful() && ((String)message.get(Message.ERROR_FIELD)).startsWith("403::")) {
                try {
                    loginParamsProvider.login();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        connector.start().get(5, TimeUnit.SECONDS);

        TopicSubscription subscription = connector.subscribe(argv[2], replayFrom, consumer).get(5, TimeUnit.SECONDS);

        System.out.println(String.format("Subscribed: %s", subscription));
    }
}
