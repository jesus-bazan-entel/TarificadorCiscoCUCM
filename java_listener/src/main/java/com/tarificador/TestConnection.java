package com.tarificador;

import javax.telephony.*;
import javax.telephony.events.ProvEv;
import javax.telephony.events.ProvInServiceEv;
import javax.telephony.events.ProvOutOfServiceEv;
import java.io.FileInputStream;
import java.util.Properties;

public class TestConnection {
    private static final String CONFIG_FILE = "/opt/tarificador/config/jtapi.properties";
    private static final String DEFAULT_CUCM_IP = "10.224.0.10";
    private static final String DEFAULT_JTAPI_USER = "jtapiuser";
    private static final String DEFAULT_JTAPI_PASSWORD = "fr4v4t3l";
    private static final String DEFAULT_APP_INFO = "TestApp";
    private static final int DEFAULT_CUCM_PORT = 2748;
    private static final boolean DEFAULT_SECURE_CONN = false;
    
    public static void main(String[] args) {
        try {
            System.out.println("Probando conexión JTAPI con CUCM...");
            
            // Cargar configuración
            Properties config = new Properties();
            try {
                FileInputStream fis = new FileInputStream(CONFIG_FILE);
                config.load(fis);
                fis.close();
                System.out.println("Configuración cargada desde: " + CONFIG_FILE);
            } catch (Exception e) {
                System.out.println("No se pudo cargar la configuración, usando valores por defecto: " + e.getMessage());
            }
            
            // Obtener parámetros de conexión
            String cucmIp = config.getProperty("cucm.ip", DEFAULT_CUCM_IP);
            int cucmPort = Integer.parseInt(config.getProperty("cucm.port", String.valueOf(DEFAULT_CUCM_PORT)));
            String jtapiUser = config.getProperty("jtapi.user", DEFAULT_JTAPI_USER);
            String jtapiPassword = config.getProperty("jtapi.password", DEFAULT_JTAPI_PASSWORD);
            String appInfo = config.getProperty("app.info", DEFAULT_APP_INFO);
            boolean secureConn = Boolean.parseBoolean(config.getProperty("secure.conn", String.valueOf(DEFAULT_SECURE_CONN)));
            
            // Construir string de conexión
            String providerString;
            if (secureConn) {
                providerString = String.format("CSTA://%s:%d;login=%s;passwd=%s;appinfo=%s", 
                                            cucmIp, cucmPort, jtapiUser, jtapiPassword, appInfo);
            } else {
                providerString = String.format("%s;login=%s;passwd=%s;appinfo=%s", 
                                            cucmIp, jtapiUser, jtapiPassword, appInfo);
            }

            System.out.println("Provider string: " + providerString);
            System.out.println("Intentando conexión con: " + providerString.replace(jtapiPassword, "********"));
            System.out.println("Provider string: " + providerString);

            JtapiPeer peer = JtapiPeerFactory.getJtapiPeer(null);
            Provider provider = peer.getProvider(providerString);

            provider.addObserver(new ProviderObserver() {
                @Override
                public void providerChangedEvent(ProvEv[] events) {
                    for (ProvEv event : events) {
                        if (event instanceof ProvInServiceEv) {
                            System.out.println("Conexión Exitosa: CUCM en servicio.");
                        } else if (event instanceof ProvOutOfServiceEv) {
                            System.out.println("Error: CUCM fuera de servicio.");
                        }
                    }
                }
            });

            // Esperar 10 segundos para verificar si la conexión es exitosa
            Thread.sleep(10000);
            provider.shutdown();
            System.out.println("Prueba de conexión completada.");

        } catch (Exception e) {
            System.err.println("Error en la conexión: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
