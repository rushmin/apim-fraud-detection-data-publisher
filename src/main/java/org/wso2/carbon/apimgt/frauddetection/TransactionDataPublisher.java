package org.wso2.carbon.apimgt.frauddetection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.databridge.agent.thrift.DataPublisher;
import org.wso2.carbon.databridge.agent.thrift.exception.AgentException;
import org.wso2.carbon.databridge.commons.Event;
import org.wso2.carbon.databridge.commons.exception.AuthenticationException;
import org.wso2.carbon.databridge.commons.exception.TransportException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Properties;

/**
 * This class publishes transaction data to WSO2 DAS.
 */
public class TransactionDataPublisher {

    private static final Log log = LogFactory.getLog(TransactionDataPublisher.class);

    private String dasHost;
    private String dasPort;
    private String dasUsername;
    private String dasPassword;

    public String streamName;
    public String streamVersion;

    private String streamId;
    private boolean ready;
    private DataPublisher dataPublisher;

    public void init() {

        try {
            readAndSetPublisherProperties();
            dataPublisher = new DataPublisher(String.format("tcp://%s:%s", dasHost, dasPort), dasUsername, dasPassword);
            streamId = dataPublisher.findStreamId(streamName, streamVersion);

            if(streamId != null){
                this.ready = true;
                log.info("Transaction data publisher has been initialized.");
            }

        } catch (MalformedURLException e) {
            logInitializationError(e);
        } catch (AgentException e) {
            logInitializationError(e);
        } catch (AuthenticationException e) {
            logInitializationError(e);
        } catch (TransportException e) {
            logInitializationError(e);
        }

    }

    public boolean isReady() {
        return ready;
    }

    public void shutdown() {
        dataPublisher.stop();
        log.info("Transaction data publisher has been shutdown.");
    }

    public void publish(Object[] transactionStreamPayload) {

        if(isReady()){

            Event transactionEvent = new Event(streamId, System.currentTimeMillis(), null, null, transactionStreamPayload);
            try {
                dataPublisher.publish(transactionEvent);
                log.debug(String.format("Published event : %s", transactionEvent.toString()));
            } catch (AgentException e) {
               log.error("Cannot publish transaction stream payload to DAS", e);
            }
        }else{
            log.error("Transaction data publisher has not been initialized properly. Cannot publish data");
        }

    }

    private void readAndSetPublisherProperties(){

        File dasPropertiesFile = new File("repository/conf/etc/fraud-detection/fraud-detection.properties");

        try {
            Properties properties = new Properties();
            properties.load(new FileInputStream(dasPropertiesFile));

            dasHost = properties.getProperty("dasHost");
            dasPort = properties.getProperty("dasPort");
            dasUsername = properties.getProperty("dasUsername");
            dasPassword = properties.getProperty("dasPassword");

            streamName = properties.getProperty("streamName");
            streamVersion = properties.getProperty("streamVersion");

            log.debug(String.format("Fraud detection DAS properties were read from the file : '%s'", dasPropertiesFile.getAbsolutePath()));

        } catch (IOException e) {
            log.warn(String.format("Cannot read Fraud detection DAS properties from the file : '%s'. Default settings will be used",
                                                                                dasPropertiesFile.getAbsolutePath()));
            setDefaultPublisherProperties();
        }

        log.debug(String.format("Fraud detection DAS properties => host : '%s', port : '%s', username : '%s', password : '%s', streamName : '%s', streamVersion : '%s'",
                                dasHost, dasPort, dasUsername, dasPassword.replaceAll(".", "x"), streamName, streamVersion));

    }

    private void setDefaultPublisherProperties(){

        dasHost = "localhost";
        dasPort = "7611";
        dasUsername = "admin";
        dasPassword = "admin";

        streamName = "transactionStream";
        streamVersion = "1.0.0";
    }

    private void logInitializationError(Exception e) {
        log.error("Cannot initialize TransactionDataPublisher.", e);
    }

}
