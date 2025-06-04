package com.tarificador;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Properties;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ConfigurationManager {
    private static final Logger logger = LogManager.getLogger(ConfigurationManager.class);
    private static final String CONFIG_FILE = "/opt/tarificador/java_listener/config.properties";
    private static final Properties config = new Properties();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper objectMapper = new ObjectMapper()
    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
    .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    public static void initialize() {
        // Cargar configuración local
        loadConfiguration();
        
        // Programar sincronización periódica con API
        scheduler.scheduleAtFixedRate(ConfigurationManager::syncConfiguration, 
                1, 15, TimeUnit.MINUTES);
        
        logger.info("Gestor de configuración inicializado");
    }
    
    public static Properties getConfig() {
        return config;
    }
    
    private static void loadConfiguration() {
        try {
            config.load(new FileInputStream(CONFIG_FILE));
            logger.info("Configuración local cargada correctamente");
        } catch (IOException e) {
            logger.error("Error cargando configuración local: {}", e.getMessage(), e);
            // Crear archivo de configuración por defecto si no existe
            saveDefaultConfiguration();
        }
    }
    
    private static void saveDefaultConfiguration() {
        try {
            // Valores por defecto
            config.setProperty("cucm.host", "190.105.250.127");
            config.setProperty("cucm.user", "jtapiuser");
            config.setProperty("cucm.password", "fr4v4t3l");
            config.setProperty("cucm.appinfo", "TarificadorApp");
            config.setProperty("api.url", "http://localhost:8000");
            config.setProperty("monitor.extensions", "all");
            config.setProperty("reconnect.delay", "60");
            config.setProperty("log.level", "INFO");
            
            // Guardar archivo
            config.store(new FileOutputStream(CONFIG_FILE), "Configuración por defecto del Tarificador");
            logger.info("Se ha creado un archivo de configuración por defecto");
        } catch (IOException e) {
            logger.error("Error creando configuración por defecto: {}", e.getMessage(), e);
        }
    }
    
    private static void syncConfiguration() {
        try {
            String apiUrl = config.getProperty("api.url", "http://localhost:8000");
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/config"))
                    .GET()
                    .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                // Analizar JSON de configuración correctamente
                Map<String, String> configMap = objectMapper.readValue(
                    response.body(), new TypeReference<Map<String, String>>() {});
                
                boolean changed = false;
                
                // Actualizar propiedades que hayan cambiado
                for (Map.Entry<String, String> entry : configMap.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    
                    if (value != null && !value.equals(config.getProperty(key))) {
                        config.setProperty(key, value);
                        changed = true;
                        logger.debug("Propiedad actualizada: {} = {}", key, value);
                    }
                }
                
                // Si hubo cambios, guardar configuración y notificar
                if (changed) {
                    config.store(new FileOutputStream(CONFIG_FILE), "Configuración sincronizada desde API");
                    logger.info("Configuración actualizada desde el servidor web");
                    
                    // Notificar para que se apliquen los cambios
                    notifyConfigurationChange();
                } else {
                    logger.debug("Configuración ya sincronizada, no hay cambios");
                }
            } else {
                logger.warn("Error sincronizando configuración. Código: {}, Respuesta: {}", 
                        response.statusCode(), response.body());
            }
            
        } catch (Exception e) {
            logger.error("Error sincronizando configuración: {}", e.getMessage(), e);
        }
    }
    
    private static void notifyConfigurationChange() {
        // En este caso, aplicamos los cambios al reiniciar la conexión JTAPI
        // si es necesario, por ejemplo si cambió la configuración del servidor CUCM
        if (config.containsKey("cucm.host") || config.containsKey("cucm.user") || 
            config.containsKey("cucm.password")) {
            logger.info("Cambios en configuración CUCM detectados, reiniciando conexión...");
            TarificadorService.startMonitoring();
        }
        
        // Reconfiguramos el logging
        LogConfig.configureLogging();
    }
    
    public static void shutdown() {
        logger.info("Deteniendo el gestor de configuración...");
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.warn("Error al detener el programador de configuración: {}", e.getMessage(), e);
        }
    }
}
