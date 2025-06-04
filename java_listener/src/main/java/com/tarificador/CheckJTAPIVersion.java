package com.tarificador;

import javax.telephony.*;
import javax.telephony.events.*;
import com.cisco.jtapi.extensions.*;
import java.util.Enumeration;
import java.util.jar.*;
import java.io.File;
import java.lang.reflect.Method;

public class CheckJTAPIVersion {
    private static final String PROVIDER_STRING = "190.105.250.127;login=jtapiuser;passwd=fr4v4t3l;appinfo=TestApp";
    
    public static void main(String[] args) {
        System.out.println("=== Información de JTAPI ===");
        
        // 1. Verificar archivo JAR
        try {
            File jarFile = new File("lib/jtapi.jar");
            JarFile jar = new JarFile(jarFile);
            
            // Buscar MANIFEST para versión
            java.util.jar.Manifest manifest = jar.getManifest();
            if (manifest != null) {
                java.util.jar.Attributes mainAttribs = manifest.getMainAttributes();
                System.out.println("\nInformación del MANIFEST:");
                for (Object key : mainAttribs.keySet()) {
                    System.out.println(key + ": " + mainAttribs.get(key));
                }
            }
            
            // Buscar clases de eventos disponibles
            System.out.println("\nClases de eventos disponibles:");
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith("Ev.class") || name.endsWith("Event.class")) {
                    if (name.contains("telephony")) {
                        System.out.println("  " + name.replace("/", ".").replace(".class", ""));
                    }
                }
            }
            jar.close();
            
        } catch (Exception e) {
            System.err.println("Error leyendo JAR: " + e.getMessage());
        }
        
        // 2. Conectar y verificar versión del provider
        try {
            System.out.println("\nConectando al CUCM...");
            JtapiPeer peer = JtapiPeerFactory.getJtapiPeer(null);
            
            // Verificar información del peer
            System.out.println("\nJTAPI Peer info:");
            System.out.println("  Nombre: " + peer.getName());
            String[] services = peer.getServices();
            if (services != null) {
                System.out.println("  Servicios disponibles:");
                for (String service : services) {
                    System.out.println("    - " + service);
                }
            }
            
            Provider provider = peer.getProvider(PROVIDER_STRING);
            Thread.sleep(2000); // Esperar conexión
            
            // Verificar si es CiscoProvider
            if (provider instanceof CiscoProvider) {
                CiscoProvider ciscoProvider = (CiscoProvider) provider;
                System.out.println("\nCiscoProvider detectado");
                
                // Intentar obtener versión
                try {
                    // Usar reflexión para buscar métodos de versión
                    Method[] methods = ciscoProvider.getClass().getMethods();
                    for (Method method : methods) {
                        if (method.getName().toLowerCase().contains("version") ||
                            method.getName().toLowerCase().contains("release")) {
                            System.out.println("  Método encontrado: " + method.getName());
                            if (method.getParameterCount() == 0) {
                                try {
                                    Object result = method.invoke(ciscoProvider);
                                    System.out.println("    Resultado: " + result);
                                } catch (Exception e) {
                                    System.out.println("    Error invocando: " + e.getMessage());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error obteniendo versión: " + e.getMessage());
                }
            }
            
            // 3. Probar eventos específicos
            System.out.println("\nProbando eventos disponibles:");
            testEventAvailability();
            
            provider.shutdown();
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void testEventAvailability() {
        // Verificar qué clases de eventos existen
        String[] eventClasses = {
            "javax.telephony.events.ConnCreatedEv",
            "javax.telephony.events.ConnConnectedEv",
            "javax.telephony.events.ConnDisconnectedEv",
            "javax.telephony.events.TermConnCreatedEv",
            "javax.telephony.events.TermConnRingingEv",
            "javax.telephony.events.TermConnAnsweredEv",
            "javax.telephony.events.TermConnActiveEv",
            "javax.telephony.events.TermConnPassiveEv",
            "javax.telephony.callcontrol.events.CallCtlConnAlertingEv",
            "javax.telephony.callcontrol.events.CallCtlConnEstablishedEv",
            "javax.telephony.callcontrol.events.CallCtlConnDialingEv",
            "javax.telephony.callcontrol.events.CallCtlConnNetworkAlertingEv",
            "javax.telephony.callcontrol.events.CallCtlConnNetworkReachedEv",
            "javax.telephony.callcontrol.events.CallCtlTermConnTalkingEv",
            "com.cisco.jtapi.extensions.CiscoTermConnTalkingEv",
            "com.cisco.jtapi.extensions.CiscoCallStartedEv",
            "com.cisco.jtapi.extensions.CiscoCallChangedEv"
        };
        
        for (String className : eventClasses) {
            try {
                Class.forName(className);
                System.out.println("  ✓ " + className);
            } catch (ClassNotFoundException e) {
                System.out.println("  ✗ " + className);
            }
        }
        
        // Verificar interfaces
        System.out.println("\nInterfaces disponibles:");
        String[] interfaces = {
            "javax.telephony.CallObserver",
            "javax.telephony.TerminalObserver",
            "javax.telephony.TerminalConnectionListener",
            "javax.telephony.callcontrol.CallControlCallObserver",
            "com.cisco.jtapi.extensions.CiscoCallObserver",
            "com.cisco.jtapi.extensions.CiscoTerminalObserver"
        };
        
        for (String interfaceName : interfaces) {
            try {
                Class.forName(interfaceName);
                System.out.println("  ✓ " + interfaceName);
            } catch (ClassNotFoundException e) {
                System.out.println("  ✗ " + interfaceName);
            }
        }
    }
}
