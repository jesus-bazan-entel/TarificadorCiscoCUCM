# cdr/__init__.py
"""
Módulo CDR (Call Detail Records) - Sistema Tarificador

Este módulo contiene toda la funcionalidad relacionada con:
- Procesamiento de registros de llamadas (CDR)
- Gestión de llamadas activas en tiempo real
- Dashboard de CDR con filtros y estadísticas
- Exportación de reportes CDR en PDF y Excel
- APIs para integración con sistema telefónico

Uso:
    from cdr import CDRService, router as cdr_router
    from cdr.dashboard import router as cdr_dashboard_router
    from cdr.exports import router as cdr_exports_router
"""

from .service import CDRService
from .models import CDR, ActiveCall
from .schemas import CallEvent, CDRFilter, CDRListResponse, ActiveCallRequest
from .router import router as api_router
from .dashboard import router as dashboard_router
from .exports import router as exports_router

__version__ = "1.0.0"
__author__ = "Sistema Tarificador"

# Exports principales
__all__ = [
    # Servicio principal
    "CDRService",
    
    # Modelos
    "CDR",
    "ActiveCall",
    
    # Schemas
    "CallEvent",
    "CDRFilter", 
    "CDRListResponse",
    "ActiveCallRequest",
    
    # Routers
    "api_router",
    "dashboard_router",
    "exports_router",
]

# Configuración del módulo
DEFAULT_CDR_CONFIG = {
    "default_zone_id": 1,
    "default_rate_per_minute": 3.0,
    "low_balance_threshold": 1.0,
    "max_export_records": 1000,
    "pagination_default_size": 10,
    "pagination_max_size": 100
}