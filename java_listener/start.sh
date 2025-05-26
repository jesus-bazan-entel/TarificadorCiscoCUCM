#!/bin/bash

# Directorio base
BASE_DIR=/opt/tarificador/java_listener

# Archivo de log para el script
LOG_FILE=/var/log/tarificador-cucm-script.log

echo "$(date) - Iniciando servicio Tarificador CUCM" >> $LOG_FILE

# Verificar que el archivo JAR existe
if [ ! -f $BASE_DIR/target/tarificador-cucm.jar ]; then
    echo "$(date) - ERROR: No se encuentra el archivo JAR" >> $LOG_FILE
    exit 1
fi

# Verificar que JTAPI existe
if [ ! -f $BASE_DIR/lib/jtapi.jar ]; then
    echo "$(date) - ERROR: No se encuentra la biblioteca JTAPI" >> $LOG_FILE
    exit 1
fi

# Construir classpath
CLASSPATH=$BASE_DIR/target/tarificador-cucm.jar:$BASE_DIR/lib/jtapi.jar

# Ejecutar aplicación con classpath
echo "$(date) - Ejecutando Java con classpath: $CLASSPATH" >> $LOG_FILE
exec java -cp $CLASSPATH com.tarificador.TarificadorService
