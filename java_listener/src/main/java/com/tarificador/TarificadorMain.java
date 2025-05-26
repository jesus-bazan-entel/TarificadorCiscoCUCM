package com.tarificador;

import javax.telephony.*;
import javax.telephony.events.*;
import javax.telephony.callcontrol.*;
import com.cisco.jtapi.extensions.*;
import java.util.concurrent.CountDownLatch;

public class TarificadorMain {
    private static final String PROVIDER_STRING = "190.105.250.127;login=jtapiuser;passwd=fr4v4t3l;appinfo=TarificadorApp";
    private static CountDownLatch latch = new CountDownLatch(1);
    
    public static void main(String[] args) {
        System.out.println("Iniciando Tarificador CUCM...");
        
        try {
            // Obtener el peer JTAPI
            JtapiPeer peer = JtapiPeerFactory.getJtapiPeer(null);
            
            // Conectar al provider
            CiscoProvider provider = (CiscoProvider) peer.getProvider(PROVIDER_STRING);
            System.out.println("Conectando al CUCM...");
            
            // Esperar a que el provider esté en servicio
            provider.addObserver(new ProviderObserver() {
                @Override
                public void providerChangedEvent(ProvEv[] events) {
                    for (ProvEv event : events) {
                        System.out.println("Provider event: " + event.getClass().getSimpleName());
                        
                        if (event instanceof ProvInServiceEv) {
                            System.out.println("Provider en servicio");
                            try {
                                setupCallMonitoring(provider);
                            } catch (Exception e) {
                                System.err.println("Error configurando monitoreo: " + e.getMessage());
                                e.printStackTrace();
                            }
                        } else if (event instanceof ProvOutOfServiceEv) {
                            System.out.println("Provider fuera de servicio");
                        } else if (event instanceof ProvShutdownEv) {
                            System.out.println("Provider shutdown");
                            latch.countDown();
                        }
                    }
                }
            });
            
            // Agregar shutdown hook para cerrar limpiamente
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Cerrando Tarificador CUCM...");
                try {
                    provider.shutdown();
                } catch (Exception e) {
                    System.err.println("Error durante shutdown: " + e.getMessage());
                }
                latch.countDown();
            }));
            
            // Mantener el programa ejecutándose
            System.out.println("Tarificador CUCM iniciado. Esperando llamadas...");
            latch.await();
            
        } catch (Exception e) {
            System.err.println("Error en Tarificador CUCM: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void setupCallMonitoring(CiscoProvider provider) throws Exception {
        // Crear el listener
        CallListener callListener = new CallListener();
        
        // Obtener todas las addresses del provider
        Address[] addresses = provider.getAddresses();
        System.out.println("Número de addresses encontradas: " + addresses.length);
        
        // Configurar monitoreo para cada address
        for (Address address : addresses) {
            if (address instanceof CiscoAddress) {
                CiscoAddress ciscoAddress = (CiscoAddress) address;
                
                try {
                    // Configurar filtros de eventos
                    CallControlAddressObserver ccao = new CallControlAddressObserver() {
                        @Override
                        public void addressChangedEvent(AddrEv[] events) {
                            // Manejar eventos de address si es necesario
                        }
                    };
                    
                    // Agregar observer de llamadas
                    ciscoAddress.addCallObserver(callListener);
                    
                    // Si es una terminal, también agregar observer allí
                    Terminal[] terminals = address.getTerminals();
                    if (terminals != null) {
                        for (Terminal terminal : terminals) {
                            if (terminal instanceof CiscoTerminal) {
                                CiscoTerminal ciscoTerm = (CiscoTerminal) terminal;
                                ciscoTerm.addCallObserver(callListener);
                                System.out.println("Monitoreando terminal: " + terminal.getName());
                            }
                        }
                    }
                    
                    System.out.println("Monitoreando address: " + address.getName());
                    
                } catch (Exception e) {
                    System.err.println("Error configurando monitoreo para " + address.getName() + ": " + e.getMessage());
                }
            }
        }
        
        System.out.println("Monitoreo de llamadas configurado");
    }
}
