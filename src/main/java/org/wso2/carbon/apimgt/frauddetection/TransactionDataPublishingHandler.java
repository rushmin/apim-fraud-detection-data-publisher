/*
 *  Copyright WSO2 Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.wso2.carbon.apimgt.frauddetection;

import com.google.gson.Gson;
import org.apache.axiom.om.OMElement;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.rest.AbstractHandler;
import org.apache.synapse.transport.passthru.util.RelayUtils;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.util.*;

/**
 * An API handler which publishes transaction data to DAS for fraud detection.
 */
public class TransactionDataPublishingHandler extends AbstractHandler implements ManagedLifecycle {

    private static final Log log = LogFactory.getLog(TransactionDataPublishingHandler.class);
    private static final String HTTP_HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HTTP_HEADER_MOCK_CLIENT_IP = "Fraud-Detection-Mock-Client-IP";

    private volatile TransactionDataPublisher transactionDataPublisher;

    public void init(SynapseEnvironment synapseEnvironment) {
        transactionDataPublisher = new TransactionDataPublisher();
        transactionDataPublisher.init();
    }

    public void destroy() {
        transactionDataPublisher.shutdown();
    }

    public boolean handleRequest(MessageContext messageContext) {
        log.debug("START : TransactionDataPublishingHandler::handleRequest()");
        publishTransactionData(messageContext);
        log.debug("END : TransactionDataPublishingHandler::handleRequest()");
        return true;
    }

    public boolean handleResponse(MessageContext messageContext) {
        return true;
    }


    private void publishTransactionData(MessageContext messageContext){

        String transactionInfoPayload = getTransactionInfoPayload(messageContext);

        if(transactionInfoPayload != null){
            Object[] transactionStreamPayload = buildTransactionStreamPayload(transactionInfoPayload, messageContext);
            log.debug(String.format("transaction stream payload => %s", transactionStreamPayload));
            transactionDataPublisher.publish((transactionStreamPayload));
        }
    }


    private String getTransactionInfoPayload(MessageContext messageContext) {

        org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) messageContext).
                getAxis2MessageContext();

        try {
            RelayUtils.buildMessage(axis2MessageContext);
        } catch (IOException e) {
            logDataPublishingException("Cannot build the incoming request message", e);
            return null;
        } catch (XMLStreamException e) {
            logDataPublishingException("Cannot build the incoming request message", e);
            return null;
        }

        Iterator iterator = messageContext.getEnvelope().getBody().getChildElements();

        String payload = null;
        if(iterator.hasNext()){
            OMElement bodyElement = (OMElement) iterator.next();
            payload = bodyElement.getText();
        }

        return payload;
    }

    private Object[] buildTransactionStreamPayload(String paymentInfoPayload, MessageContext messageContext) {

        // Parse the payment info JSON payload.
        Gson gson = new Gson();
        Map<String, Object> paymentPayload = gson.fromJson(paymentInfoPayload, HashMap.class);

        // Extract credit card info
        Map<String, Object> creditCardInfo = (Map<String, Object>) ((Map<String, Object>)((List)((Map<String, Object>) paymentPayload.get("payer")).get("funding_instruments")).get(0)).get("credit_card");

        // Extract shipping info
        Map<String, String> shippingInfo = (Map<String, String>) ((Map<String, Object>) paymentPayload.get("shipment")).get("shipping_address");

        // Extract transaction info
        Map<String, Object> transactionAmountInfo = (Map<String, Object>) ((Map<String, Object>)((List)paymentPayload.get("transactions")).get(0)).get("amount");

        // Extract order info
        Map<String, Object> orderInfo = (Map<String, Object>) ((Map<String, Object>)((List)paymentPayload.get("transactions")).get(0)).get("order");

        String transactionId = (String) paymentPayload.get("id");
        long creditCardNumber = Long.parseLong((String) creditCardInfo.get("number"));
        double transactionAmount = Double.parseDouble((String) transactionAmountInfo.get("total"));
        String currency = (String) transactionAmountInfo.get("currency");
        String email = (String) ((Map<String, Object>) paymentPayload.get("payer")).get("email");
        String shippingAddress = getShippingAddress(shippingInfo);
        String billingAddress = getBillingAddress(creditCardInfo);
        String ip = getClientIPAddress(messageContext);
        String itemNo = (String) orderInfo.get("item_number");
        int quantity = ((Double) orderInfo.get("quantity")).intValue();
        long timestamp = System.currentTimeMillis();

        return new Object[]{transactionId, creditCardNumber, transactionAmount, currency, email, shippingAddress, billingAddress, ip, itemNo, quantity, timestamp};
    }

    private String getClientIPAddress(MessageContext messageContext) {

        // Client IP should be retrieved based on the scenario. Following order should be followed.
        // 1) If the client IP is mocked, then there will be an HTTP header named 'FRAUD-DETECTION-MOCK-CLIENT-IP'
        // 2) API GW is fronted by a load balancer. Client IP should be retrieved from the 'X-Forwarded-For' header.
        // 3) Versioned API is called directly (No load balancer). Client IP should be retrieved from the 'REMOTE_ADDR' header

        String clientIPAddress = null;

        org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        Map<String, String> transportHeaders  = (Map) axis2MessageContext.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);

        // Check whether the mock IP has been set.
        String mockClientIP = (String) transportHeaders.get(HTTP_HEADER_MOCK_CLIENT_IP);
        if (mockClientIP != null && !mockClientIP.isEmpty()) {

            clientIPAddress = mockClientIP;

            if(log.isDebugEnabled()){
                log.debug(String.format("Retrieved the client IP '%s' from the HTTP header '%s'", clientIPAddress, HTTP_HEADER_MOCK_CLIENT_IP));
            }
            return clientIPAddress;
        }

        // Check whether the request comes from the load balancer. If yes get the client IP from the 'X-Forwarded-For' header.
        String xForwardedForHeaderValue = (String) transportHeaders.get(HTTP_HEADER_X_FORWARDED_FOR);
        if (xForwardedForHeaderValue != null && !xForwardedForHeaderValue.isEmpty()) {

            clientIPAddress = xForwardedForHeaderValue.split(",")[0];

            if(log.isDebugEnabled()){
                log.debug(String.format("Retrieved the client IP '%s' from the HTTP header '%s'", clientIPAddress, HTTP_HEADER_X_FORWARDED_FOR));
            }

            return clientIPAddress;
        }

        // No special handling. Just get the address of the request sender.

        clientIPAddress = (String) axis2MessageContext.getProperty(org.apache.axis2.context.MessageContext.REMOTE_ADDR);

        if(log.isDebugEnabled()){
            log.debug(String.format("Retrieved the client IP '%s' from the HTTP header '%s'", clientIPAddress, org.apache.axis2.context.MessageContext.REMOTE_ADDR));
        }

        return clientIPAddress;

    }

    private String getBillingAddress(Map<String, Object> creditCardInfo) {
        Map<String, String> billingAddressInfo = (Map<String, String>) creditCardInfo.get("billing_address");
        return String.format("%s, %s, %s, %s, %s", billingAddressInfo.get("line1"), billingAddressInfo.get("city"), billingAddressInfo.get("state"), billingAddressInfo.get("postal_code"), billingAddressInfo.get("country_code"));
    }

    private String getShippingAddress(Map<String, String> shippingInfo) {
        return String.format("%s, %s, %s, %s, %s", shippingInfo.get("line1"), shippingInfo.get("city"), shippingInfo.get("state"), shippingInfo.get("postal_code"), shippingInfo.get("country_code"));
    }


    private void logDataPublishingException(String reason, Exception e) {
        log.error(String.format("Cannot publish transaction data. Reason : %s", reason), e);
    }

}
