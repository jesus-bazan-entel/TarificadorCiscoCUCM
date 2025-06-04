package com.tarificador;

import javax.telephony.*;
import javax.telephony.events.ProvEv;
import javax.telephony.events.ProvInServiceEv;
import javax.telephony.events.ProvOutOfServiceEv;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TarificadorService {
    private static final Logger logger = LogManager.getLogger(TarificadorService.class);
    private static Provider provider;
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private static boolean isShutdown = false;

    public static void main(String[] args) {
        logger.info("Iniciando servicio de Tarificador CUCM...");

        // Configurar logging
        LogConfig.configureLogging();

        // Inicializar el gestor de configuración
        ConfigurationManager.initialize();

        // Iniciar servicio de monitoreo
        startMonitoring();

        // Programar verificación de conexión cada 5 minutos
        scheduler.scheduleAtFixedRate(TarificadorService::checkConnection,
                5, 5, TimeUnit.MINUTES);

        // Mostrar información de la versión
        logger.info("Tarificador CUCM v1.0 iniciado");

        // Mantener el servicio en ejecución
        Runtime.getRuntime().addShutdownHook(new Thread(TarificadorService::shutdown));

        // Mantener el proceso vivo
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            logger.warn("Hilo principal interrumpido", e);
        }
    }

    public static void startMonitoring() {
        try {
            String host = ConfigurationManager.getConfig().getProperty("cucm.host", "190.105.250.127");
            String user = ConfigurationManager.getConfig().getProperty("cucm.user", "jtapiuser");
            String password = ConfigurationManager.getConfig().getProperty("cucm.password", "fr4v4t3l");
            String appInfo = ConfigurationManager.getConfig().getProperty("cucm.appinfo", "TarificadorApp");

            String providerString = host + ";login=" + user + ";passwd=" + password + ";appinfo=" + appInfo;

            logger.info("Conectando a CUCM: {}", host);

            JtapiPeer peer = JtapiPeerFactory.getJtapiPeer(null);
            provider = peer.getProvider(providerString);

            provider.addObserver(new ProviderObserver() {
                @Override
                public void providerChangedEvent(ProvEv[] events) {
                    for (ProvEv event : events) {
                        if (event instanceof ProvInServiceEv) {
                            logger.info("Conexión Exitosa: CUCM en servicio.");
                            registerCallObserver();
                        } else if (event instanceof ProvOutOfServiceEv) {
                            logger.warn("Error: CUCM fuera de servicio. Intentando reconectar...");

                            if (!isShutdown) {
                                int reconnectDelay = Integer.parseInt(
                                        ConfigurationManager.getConfig().getProperty("reconnect.delay", "60"));

                                scheduler.schedule(TarificadorService::restartConnection,
                                        reconnectDelay, TimeUnit.SECONDS);
                            }
                        }
                    }
                }
            });

        } catch (Exception e) {
            logger.error("Error estableciendo conexión con CUCM: {}", e.getMessage(), e);

            // Programar reintento
            if (!isShutdown) {
                int reconnectDelay = Integer.parseInt(
                        ConfigurationManager.getConfig().getProperty("reconnect.delay", "60"));

                scheduler.schedule(TarificadorService::startMonitoring,
                        reconnectDelay, TimeUnit.SECONDS);
            }
        }
    }

    private static void registerCallObserver() {
        try {
            logger.info("Registrando CallListener para monitoreo de llamadas...");

            // Registrar el observador
            CallListener callListener = new CallListener();

            // Obtener direcciones (extensiones) a monitorear
            String extensionsToMonitor = ConfigurationManager.getConfig().getProperty("monitor.extensions", "all");

            if ("all".equalsIgnoreCase(extensionsToMonitor)) {
                // Monitorear todas las addresses (extensiones)
                Address[] addresses = provider.getAddresses();
                if (addresses != null) {
                    int count = 0;
                    for (Address address : addresses) {
                        try {
                            address.addCallObserver(callListener);
                            logger.info("Monitoreando extensión: {}", address.getName());
                            count++;
                        } catch (Exception e) {
                            logger.error("Error al registrar observador para extensión {}: {}", 
                                       address.getName(), e.getMessage(), e);
                        }
                    }
                    logger.info("Observador registrado para {} extensiones", count);
                } else {
                    logger.warn("No se encontraron extensiones para monitorear");
                }
            } else {
                // Monitorear solo extensiones específicas
                String[] extensions = extensionsToMonitor.split(",");
                for (String extension : extensions) {
                    try {
                        Address address = provider.getAddress(extension.trim());
                        if (address != null) {
                            address.addCallObserver(callListener);
                            logger.info("Monitoreando extensión: {}", extension.trim());
                        } else {
                            logger.warn("No se pudo encontrar la extensión: {}", extension.trim());
                        }
                    } catch (Exception e) {
                        logger.error("Error al registrar observador para extensión {}: {}", 
                                   extension.trim(), e.getMessage(), e);
                    }
                }
            }

            logger.info("Observador de llamadas registrado con éxito");

        } catch (Exception e) {
            logger.error("Error registrando observador de llamadas: {}", e.getMessage(), e);
        }
    }

    private static void restartConnection() {
        logger.info("Reiniciando conexión con CUCM...");

        try {
            if (provider != null) {
                provider.shutdown();
            }
        } catch (Exception e) {
            logger.error("Error al cerrar conexión existente: {}", e.getMessage(), e);
        }

        // Iniciar nueva conexión
        startMonitoring();
    }

    private static void checkConnection() {
        if (provider == null || provider.getState() != Provider.IN_SERVICE) {
            logger.warn("Conexión CUCM no disponible. Intentando reconectar...");
            restartConnection();
        } else {
            logger.debug("Conexión CUCM OK. Estado: {}", provider.getState());
        }
    }

    public static void shutdown() {
        logger.info("Deteniendo servicio de Tarificador CUCM...");
        isShutdown = true;

        if (provider != null) {
            try {
                provider.shutdown();
            } catch (Exception e) {
                logger.error("Error al cerrar proveedor JTAPI: {}", e.getMessage(), e);
            }
        }

        ConfigurationManager.shutdown();

        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.warn("Interrupción durante cierre de scheduler", e);
        }

        logger.info("Servicio finalizado");
    }
}
