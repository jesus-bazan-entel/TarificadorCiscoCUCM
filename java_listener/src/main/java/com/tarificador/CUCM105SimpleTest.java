package com.tarificador;

import javax.telephony.*;

public class CUCM105SimpleTest {
    // MODIFICAR ESTOS VALORES SEGÚN SEA NECESARIO
    private static final String CUCM_HOST = "10.224.0.10";
    private static final String JTAPI_USER = "jtapiuser";
    private static final String JTAPI_PASSWORD = "fr4v4t3l";
    
    public static void main(String[] args) {
        try {
            System.out.println("\n=== TEST DE CONECTIVIDAD JTAPI PARA CUCM 10.5 ===");
            System.out.println("Host CUCM: " + CUCM_HOST);
            System.out.println("Usuario: " + JTAPI_USER);
            
            // Intentar cargar algunas clases para verificar la instalación
            try {
                Class.forName("com.cisco.jtapi.JtapiPeerImpl");
                System.out.println("Biblioteca JTAPI principal encontrada correctamente");
            } catch (Exception e) {
                System.out.println("Error: No se pudo cargar la clase principal JTAPI: " + e.getMessage());
            }
            
            // Activar diagnósticos JTAPI
            System.setProperty("com.cisco.jtapi.diagnostics", "true");
            System.setProperty("com.cisco.jtapi.diagnostics.directory", "/tmp");
            
            // Definir propiedades para CUCM 10.5
            System.setProperty("com.cisco.cti.client.version", "10.5");
            System.setProperty("com.cisco.jtapi.client.version", "10.5");
            
            JtapiPeer peer = JtapiPeerFactory.getJtapiPeer(null);
            
            // Probar múltiples formatos de conexión
            String[] testStrings = {
                CUCM_HOST + ";login=" + JTAPI_USER + ";passwd=" + JTAPI_PASSWORD + ";appinfo=TestApp",
                CUCM_HOST + ";login=" + JTAPI_USER + ";passwd=" + JTAPI_PASSWORD + ";appinfo=TestApp;desiredServerVersion=10.5",
                CUCM_HOST + ":2748;login=" + JTAPI_USER + ";passwd=" + JTAPI_PASSWORD + ";appinfo=TestApp",
                CUCM_HOST + ";login=" + JTAPI_USER + ";passwd=" + JTAPI_PASSWORD + ";appinfo=TestApp;axl=true"
            };
            
            boolean success = false;
            String workingString = "";
            
            for (String providerString : testStrings) {
                System.out.println("\nProbando conexión con: " + providerString);
                
                try {
                    Provider provider = peer.getProvider(providerString);
                    System.out.println("✓ CONEXIÓN EXITOSA con este formato!");
                    System.out.println("Estado del Provider: " + provider.getState());
                    
                    success = true;
                    workingString = providerString;
                    
                    // Intentar obtener extensiones
                    try {
                        if (provider.getState() == Provider.IN_SERVICE) {
                            System.out.println("Obteniendo extensiones...");
                            Address[] addresses = provider.getAddresses();
                            System.out.println("Extensiones encontradas: " + addresses.length);
                            
                            if (addresses.length > 0) {
                                System.out.println("Primeras 3 extensiones:");
                                for (int i = 0; i < Math.min(3, addresses.length); i++) {
                                    System.out.println("  - " + addresses[i].getName());
                                }
                            }
                        } else {
                            System.out.println("Esperando que el proveedor esté en servicio...");
                            for (int i = 0; i < 5; i++) {
                                Thread.sleep(1000);
                                System.out.println("Estado: " + provider.getState());
                                if (provider.getState() == Provider.IN_SERVICE) {
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("Error al listar extensiones: " + e.getMessage());
                    }
                    
                    provider.shutdown();
                    break;
                } catch (Exception e) {
                    System.out.println("✗ ERROR: " + e.getMessage());
                }
            }
            
            if (success) {
                System.out.println("\n✅ PRUEBA EXITOSA!");
                System.out.println("String de conexión que funciona: " + workingString);
                System.out.println("Usa este formato en tu aplicación.");
            } else {
                System.out.println("\n❌ TODOS LOS FORMATOS FALLARON");
                System.out.println("Revisa que el servicio CTI Manager esté activo en CUCM");
                System.out.println("Verifica que el usuario tenga los roles correctos");
                System.out.println("Asegúrate que no haya bloqueos de firewall");
            }
            
        } catch (Exception e) {
            System.out.println("Error general: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
