#!/bin/bash
# Script para transferir todas las bibliotecas del sistema tarificador a un servidor de producción
# Ejecutar este script desde el servidor de desarrollo

# Configuración
SERVER_USER="usuario"            # Cambiar al usuario correcto del servidor de producción
SERVER_HOST="servidor-produccion"  # Cambiar a la dirección IP o hostname de producción
TEMP_DIR="/tmp/tarificador_libs"
PROJECT_DIR="/opt/tarificador"   # Ruta del proyecto en el servidor de desarrollo
VENV_PATH="/opt/tarificador/venv" # Ruta del entorno virtual (si se usa)
PROD_VENV_PATH="/opt/tarificador/venv" # Ruta del entorno virtual en producción

# Colores para mensajes
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Crear directorio temporal
echo -e "${GREEN}Creando directorio temporal...${NC}"
mkdir -p $TEMP_DIR/wheels

# Activar entorno virtual si existe
if [ -d "$VENV_PATH" ]; then
  echo -e "${GREEN}Activando entorno virtual...${NC}"
  source $VENV_PATH/bin/activate
fi

# Método 1: Extraer importaciones del código fuente
echo -e "${GREEN}Analizando importaciones en archivos Python...${NC}"
cd $PROJECT_DIR
IMPORTS=$(find . -name "*.py" -type f -exec grep -E "^import |^from .* import" {} \; | \
          sed 's/^import \([a-zA-Z0-9_]*\).*/\1/g; s/^from \([a-zA-Z0-9_]*\).*/\1/g' | \
          grep -v "^\..*" | sort | uniq)

# Método 2: Capturar dependencias instaladas
echo -e "${GREEN}Capturando dependencias instaladas...${NC}"
if command -v pip &> /dev/null; then
  pip freeze > $TEMP_DIR/installed_packages.txt
  echo -e "${GREEN}✅ Lista de paquetes instalados guardada en: ${TEMP_DIR}/installed_packages.txt${NC}"
else
  echo -e "${RED}⚠️ Comando pip no encontrado${NC}"
fi

# Método 3: Usar pipreqs si está disponible
if command -v pipreqs &> /dev/null; then
  echo -e "${GREEN}Ejecutando pipreqs para análisis automático de dependencias...${NC}"
  pipreqs --force $PROJECT_DIR --output $TEMP_DIR/pipreqs_requirements.txt
  echo -e "${GREEN}✅ Requisitos generados por pipreqs guardados en: ${TEMP_DIR}/pipreqs_requirements.txt${NC}"
else
  echo -e "${YELLOW}⚠️ pipreqs no está instalado. Instalarlo mejoraría la detección de dependencias.${NC}"
  echo -e "${YELLOW}   Para instalarlo: pip install pipreqs${NC}"
fi

# Combinar todas las fuentes de dependencias
echo -e "${GREEN}Combinando todas las fuentes de dependencias...${NC}"
echo "$IMPORTS" > $TEMP_DIR/extracted_imports.txt

# Crear lista final de requisitos
echo -e "${GREEN}Creando lista final de requisitos...${NC}"
touch $TEMP_DIR/final_requirements.txt

# Agregar dependencias principales que sabemos que son necesarias
echo -e "xlwt\nopenpyxl\nfastapi\nuvicorn\nsqlalchemy\npydantic\npasslib\npython-multipart\nstarlette\npsycopg2-binary\nfastapi-login\nweasyprint\njinja2\njwt\nxlsxwriter\nxlrd\npandas" > $TEMP_DIR/final_requirements.txt

# Agregar otras dependencias encontradas si hay archivos de requisitos
if [ -f "$TEMP_DIR/installed_packages.txt" ]; then
  cat $TEMP_DIR/installed_packages.txt >> $TEMP_DIR/final_requirements.txt
fi

if [ -f "$TEMP_DIR/pipreqs_requirements.txt" ]; then
  cat $TEMP_DIR/pipreqs_requirements.txt >> $TEMP_DIR/final_requirements.txt
fi

# Limpiar y eliminar duplicados, versiones y comentarios
cat $TEMP_DIR/final_requirements.txt | grep -v "^#" | sed 's/==.*//g' | sed 's/>.*//g' | sed 's/<.*//g' | sort -u > $TEMP_DIR/clean_requirements.txt

echo -e "${GREEN}Lista final de paquetes a descargar:${NC}"
cat $TEMP_DIR/clean_requirements.txt

# Descargar todas las bibliotecas y sus dependencias
echo -e "${GREEN}Descargando bibliotecas y dependencias...${NC}"
pip download -d $TEMP_DIR/wheels -r $TEMP_DIR/clean_requirements.txt

# Crear script de instalación para el servidor de producción
echo -e "${GREEN}Creando script de instalación para el servidor de producción...${NC}"
cat > $TEMP_DIR/install.sh << EOF
#!/bin/bash
# Script de instalación para el servidor de producción

# Colores para mensajes
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "\${GREEN}Iniciando instalación de bibliotecas para el sistema tarificador...${NC}"

# Activar entorno virtual
if [ -d "$PROD_VENV_PATH" ]; then
  echo -e "\${GREEN}Activando entorno virtual...${NC}"
  source $PROD_VENV_PATH/bin/activate
else
  echo -e "\${RED}⚠️ Entorno virtual no encontrado en $PROD_VENV_PATH${NC}"
  exit 1
fi

# Instalar bibliotecas desde wheels
echo -e "\${GREEN}Instalando bibliotecas desde archivos wheel...${NC}"
pip install --no-index --find-links=./wheels -r clean_requirements.txt

# Verificar instalación
echo -e "\${GREEN}Verificando instalación:${NC}"
failed_imports=0
success_imports=0

for lib in \$(cat clean_requirements.txt); do
  # Normalizar nombre de la biblioteca para importación
  import_name=\$(echo \$lib | tr '-' '_' | tr '[:upper:]' '[:lower:]')
  
  # Intentar importar la biblioteca
  if python -c "import \$import_name" 2>/dev/null; then
    echo -e "\${GREEN}✅ \$lib importado correctamente${NC}"
    success_imports=\$((success_imports + 1))
  else
    # Algunos paquetes tienen nombres diferentes para importar
    case \$lib in
      "psycopg2-binary")
        if python -c "import psycopg2" 2>/dev/null; then
          echo -e "\${GREEN}✅ \$lib importado correctamente como psycopg2${NC}"
          success_imports=\$((success_imports + 1))
        else
          echo -e "\${RED}❌ \$lib no se pudo importar${NC}"
          failed_imports=\$((failed_imports + 1))
        fi
        ;;
      "python-multipart")
        if python -c "import multipart" 2>/dev/null; then
          echo -e "\${GREEN}✅ \$lib importado correctamente como multipart${NC}"
          success_imports=\$((success_imports + 1))
        else
          echo -e "\${RED}❌ \$lib no se pudo importar${NC}"
          failed_imports=\$((failed_imports + 1))
        fi
        ;;
      *)
        echo -e "\${RED}❌ \$lib no se pudo importar${NC}"
        failed_imports=\$((failed_imports + 1))
        ;;
    esac
  fi
done

echo -e "\${GREEN}Instalación completada.${NC}"
echo -e "\${GREEN}Bibliotecas instaladas correctamente: \$success_imports${NC}"
if [ \$failed_imports -gt 0 ]; then
  echo -e "\${RED}Bibliotecas con problemas: \$failed_imports${NC}"
  echo -e "\${YELLOW}Nota: Algunos paquetes pueden ser subdependencias y no necesitan ser importados directamente.${NC}"
fi
EOF

# Dar permisos de ejecución
chmod +x $TEMP_DIR/install.sh

# Comprimir todo
echo -e "${GREEN}Comprimiendo archivos...${NC}"
cd $TEMP_DIR
tar -czvf ../tarificador_libs.tar.gz *

# Transferir al servidor
echo -e "${GREEN}Transfiriendo archivos a $SERVER_HOST...${NC}"
#scp ../tarificador_libs.tar.gz $SERVER_USER@$SERVER_HOST:/tmp/

# Instrucciones
echo -e "\n${GREEN}Transferencia completada. Para instalar en el servidor de producción, ejecuta:${NC}"
echo -e "${YELLOW}cd /tmp"
echo -e "tar -xzvf tarificador_libs.tar.gz"
echo -e "bash install.sh${NC}"

echo -e "\n${GREEN}Nota: El archivo comprimido también está disponible en: /tmp/tarificador_libs.tar.gz${NC}"
