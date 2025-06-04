package com.tarificador;

import javax.telephony.*;
import javax.telephony.events.*;
import java.util.Scanner;

public class ListMonitoredExtensions {
    private static final String PROVIDER_STRING = "190.105.250.127;login=jtapiuser;passwd=fr4v4t3l;appinfo=TestApp";
    
    public static void main(String[] args) {
        try {
            System.out.println("=== Listando extensiones disponibles ===");
            
            JtapiPeer peer = JtapiPeerFactory.getJtapiPeer(null);
            Provider provider = peer.getProvider(PROVIDER_STRING);
            
            provider.addObserver(new ProviderObserver() {
                @Override
                public void providerChangedEvent(ProvEv[] events) {
                    for (ProvEv event : events) {
                        if (event instanceof ProvInServiceEv) {
                            System.out.println("Provider conectado");
                            try {
                                Address[] addresses = provider.getAddresses();
                                System.out.println("\nTotal de extensiones: " + addresses.length);
                                
                                System.out.println("\nPrimeras 20 extensiones:");
                                for (int i = 0; i < Math.min(20, addresses.length); i++) {
                                    System.out.println((i+1) + ". " + addresses[i].getName());
                                }
                                
                                if (addresses.length > 20) {
                                    System.out.println("... y " + (addresses.length - 20) + " más");
                                }
                                
                                // Buscar extensiones específicas
                                System.out.println("\nIngresa un número de extensión para buscar (o 'q' para salir):");
                                Scanner scanner = new Scanner(System.in);
                                
                                while (true) {
                                    System.out.print("> ");
                                    String input = scanner.nextLine();
                                    
                                    if (input.equals("q")) {
                                        break;
                                    }
                                    
                                    boolean found = false;
                                    for (Address addr : addresses) {
                                        if (addr.getName().contains(input)) {
                                            System.out.println("Encontrado: " + addr.getName());
                                            found = true;
                                        }
                                    }
                                    
                                    if (!found) {
                                        System.out.println("No se encontró la extensión: " + input);
                                    }
                                }
                                
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            });
            
            System.out.println("Conectando...");
            Thread.sleep(5000);
            
            provider.shutdown();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
