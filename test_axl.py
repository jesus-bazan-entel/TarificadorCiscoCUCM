from zeep import Client, Settings
from requests import Session
from requests.auth import HTTPBasicAuth
from zeep.transports import Transport
from zeep.plugins import HistoryPlugin
from zeep.exceptions import Fault
from lxml import etree
import urllib3
import logging

# Configurar logging
logging.basicConfig(level=logging.DEBUG)
logging.getLogger('zeep').setLevel(logging.DEBUG)

# Desactivar advertencias SSL
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# Crear plugin para ver el tráfico SOAP
history = HistoryPlugin()

# Configuración
CUCM_ADDRESS = '190.105.250.127'
CUCM_USERNAME = 'admin'
CUCM_PASSWORD = 'fr4v4t3l'
WSDL_FILE = 'schema/AXLAPI.wsdl'

# Crear sesión
session = Session()
session.verify = False
session.auth = HTTPBasicAuth(CUCM_USERNAME, CUCM_PASSWORD)

# Configuración Zeep
settings = Settings(strict=False, xml_huge_tree=True)
transport = Transport(session=session, timeout=30)

# Crear cliente
try:
    client = Client(WSDL_FILE, transport=transport, settings=settings, plugins=[history])
    
    # Mostrar información del WSDL
    print("WSDL cargado correctamente")
    print(f"Servicios disponibles: {[service.name for service in client.wsdl.services.values()]}")
    
    # Intentar conexión
    service = client.create_service(
        '{http://www.cisco.com/AXLAPIService/}AXLAPIBinding',
        f'https://{CUCM_ADDRESS}:8443/axl/'
    )
    
    print("Servicio creado correctamente")
    print(f"URL del servicio: https://{CUCM_ADDRESS}:8443/axl/")
    
    # Intentar una operación simple
    try:
        print("Intentando obtener la versión de CUCM...")
        versions = service.getCCMVersion()
        print("¡Éxito! Información de versión:")
        print(versions)
    except Fault as fault:
        print(f"Error SOAP: {fault}")
        
        # Mostrar la última petición y respuesta
        print("\nÚltima petición enviada:")
        print(etree.tostring(history.last_sent["envelope"], pretty_print=True).decode())
        
        if history.last_received is not None:
            print("\nÚltima respuesta recibida:")
            print(etree.tostring(history.last_received["envelope"], pretty_print=True).decode())
    
except Exception as e:
    print(f"Error de inicialización: {e}")
