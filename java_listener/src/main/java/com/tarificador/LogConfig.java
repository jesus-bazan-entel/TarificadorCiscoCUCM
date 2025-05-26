package com.tarificador;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.Level;

public class LogConfig {
    private static final Logger logger = LogManager.getLogger(LogConfig.class);
    
    public static void configureLogging() {
        try {
            String logLevel = ConfigurationManager.getConfig().getProperty("log.level", "INFO");
            
            Level level;
            switch (logLevel.toUpperCase()) {
                case "DEBUG":
                    level = Level.DEBUG;
                    break;
                case "WARN":
                    level = Level.WARN;
                    break;
                case "ERROR":
                    level = Level.ERROR;
                    break;
                case "TRACE":
                    level = Level.TRACE;
                    break;
                case "INFO":
                default:
                    level = Level.INFO;
                    break;
            }
            
            Configurator.setRootLevel(level);
            logger.info("Nivel de log configurado a: {}", logLevel);
            
        } catch (Exception e) {
            // Configuración por defecto
            Configurator.setRootLevel(Level.INFO);
            logger.error("Error configurando nivel de log: {}", e.getMessage(), e);
        }
    }
}