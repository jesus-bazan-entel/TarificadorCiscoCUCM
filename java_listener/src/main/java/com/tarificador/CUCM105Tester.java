package com.tarificador;

import javax.telephony.*;

public class CUCM105Tester {
    public static void main(String[] args) {
        try {
            System.out.println("Test de conexión JTAPI para CUCM 10.5");
            
            // Configuración específica para CUCM 10.5
            String host = "10.224.0.10";
            String user = "jtapiuser";
            String password = "fr4v4t3l";
            
            // Formato especial para CUCM 10.5
            String providerString = host + ";login=" + user + ";passwd=" + password + 
                                   ";appinfo=CUCMTest;axl=true;xclientversion=10.5.2";
            
            System.out.println("Usando string de conexión: " + providerString);
            
            // Establecer propiedades específicas para 10.5
            System.setProperty("com.cisco.jtapi.defaultversion", "10.5");
            System.setProperty("com.cisco.jtapi.forceversion", "true");
            
            // Intentar conexión
            JtapiPeer peer = JtapiPeerFactory.getJtapiPeer(null);
            Provider provider = peer.getProvider(providerString);
            
            System.out.println("¡Conexión exitosa! Estado: " + provider.getState());
            Thread.sleep(5000); // Esperar para ver si cambia de estado
            
            provider.shutdown();
            System.out.println("Prueba completada con éxito");
            
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
