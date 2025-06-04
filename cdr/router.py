# cdr/router.py
from fastapi import APIRouter, HTTPException, Query, Request
from sqlalchemy.orm import Session
from typing import Optional, List, Dict
import logging
import json

from .service import CDRService
from .schemas import CallEvent, CDRFilter, CDRListResponse, ActiveCallRequest
from main import SessionLocal  # Importar del main.py existente

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/cdr", tags=["CDR"])

@router.post("/")
def create_cdr(event: CallEvent):
    """
    Crea un nuevo registro CDR - Endpoint extraído del main.py
    """
    db = SessionLocal()
    
    try:
        cdr_service = CDRService(db)
        result = cdr_service.create_cdr(event)
        return result
    except Exception as e:
        logger.error(f"Error creando CDR: {e}")
        raise HTTPException(status_code=500, detail=f"Error guardando CDR: {str(e)}")
    finally:
        db.close()

@router.post("/rejected")
def rejected_call(event: CallEvent):
    """
    Registra una llamada rechazada por saldo insuficiente - Extraído del main.py
    """
    db = SessionLocal()
    
    try:
        # Modificar el evento para marcarlo como rechazado
        event.status = "rejected_insufficient_balance"
        event.duration_seconds = 0
        event.duration_billable = 0
        
        cdr_service = CDRService(db)
        result = cdr_service.create_cdr(event)
        
        return {"message": "Llamada rechazada registrada", **result}
    except Exception as e:
        logger.error(f"Error registrando llamada rechazada: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        db.close()

@router.get("/list")
def get_cdr_list(
    phone_number: Optional[str] = Query(None, description="Buscar en origen o destino"),
    calling_number: Optional[str] = Query(None, description="Número origen específico"),
    called_number: Optional[str] = Query(None, description="Número destino específico"),
    start_date: Optional[str] = Query(None, description="Fecha inicio (YYYY-MM-DD)"),
    end_date: Optional[str] = Query(None, description="Fecha fin (YYYY-MM-DD)"),
    min_duration: Optional[int] = Query(0, description="Duración mínima en segundos"),
    status: Optional[str] = Query(None, description="Estado de la llamada"),
    direction: Optional[str] = Query(None, description="Dirección de la llamada"),
    page: int = Query(1, ge=1, description="Página"),
    per_page: int = Query(10, ge=1, le=100, description="Registros por página")
) -> CDRListResponse:
    """
    Obtiene lista paginada de CDR con filtros - Funcionalidad extraída del main.py
    """
    db = SessionLocal()
    
    try:
        filters = CDRFilter(
            phone_number=phone_number,
            calling_number=calling_number,
            called_number=called_number,
            start_date=start_date,
            end_date=end_date,
            min_duration=min_duration,
            status=status,
            direction=direction,
            page=page,
            per_page=per_page
        )
        
        cdr_service = CDRService(db)
        return cdr_service.get_cdr_list(filters)
    except Exception as e:
        logger.error(f"Error obteniendo lista CDR: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        db.close()

@router.get("/stats/hourly")
def get_hourly_stats(
    start_date: Optional[str] = Query(None),
    end_date: Optional[str] = Query(None)
):
    """
    Obtiene estadísticas de llamadas por hora - Extraído del main.py
    """
    db = SessionLocal()
    
    try:
        cdr_service = CDRService(db)
        return cdr_service.get_call_stats_by_hour(start_date, end_date)
    except Exception as e:
        logger.error(f"Error obteniendo estadísticas por hora: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        db.close()

# === ENDPOINTS DE LLAMADAS ACTIVAS ===

@router.get("/active-calls")
async def get_active_calls():
    """
    Obtiene la lista de llamadas activas - Extraído del main.py
    """
    db = SessionLocal()
    
    try:
        cdr_service = CDRService(db)
        active_calls = cdr_service.get_active_calls()
        return active_calls
    except Exception as e:
        logger.error(f"Error obteniendo llamadas activas: {e}")
        return {"status": "error", "message": str(e)}
    finally:
        db.close()

@router.post("/active-calls")
async def report_active_call(call_data: dict):
    """
    Reporta una llamada activa - Endpoint extraído del main.py
    """
    db = SessionLocal()
    
    try:
        logger.info(f"Recibido reporte de llamada activa: {call_data}")
        
        # Validar datos requeridos
        call_id = call_data.get("call_id")
        if not call_id:
            return {"status": "error", "message": "call_id es requerido"}
        
        # Crear objeto de request
        active_call_request = ActiveCallRequest(
            call_id=call_id,
            calling_number=call_data.get("calling_number") or call_data.get("origin", ""),
            called_number=call_data.get("called_number") or call_data.get("destination", ""),
            direction=call_data.get("direction", "unknown"),
            zone=call_data.get("zone", "Desconocida"),
            start_time=call_data.get("start_time", ""),
            current_duration=call_data.get("current_duration") or call_data.get("duration", 0),
            current_cost=call_data.get("current_cost") or call_data.get("estimatedCost", 0),
            connection_id=call_data.get("connection_id") or call_id
        )
        
        cdr_service = CDRService(db)
        result = cdr_service.report_active_call(active_call_request)
        
        return result
    except Exception as e:
        logger.error(f"Error reportando llamada activa: {e}")
        return {"status": "error", "message": str(e)}
    finally:
        db.close()

@router.delete("/active-calls/{call_id}")
async def remove_active_call(call_id: str):
    """
    Elimina una llamada activa - Endpoint extraído del main.py
    """
    db = SessionLocal()
    
    try:
        cdr_service = CDRService(db)
        result = cdr_service.remove_active_call(call_id)
        return result
    except Exception as e:
        logger.error(f"Error eliminando llamada activa: {e}")
        return {"status": "error", "message": str(e)}
    finally:
        db.close()

# === ENDPOINTS DE TESTING Y VALIDACIÓN ===

@router.post("/test-validation")
async def test_cdr_validation(request: Request):
    """
    Endpoint para probar validación de datos CDR - Extraído del main.py
    NO guarda nada en la base de datos, solo valida el modelo
    """
    try:
        # Obtener datos raw
        raw_data = await request.json()
        logger.info("🧪 TESTING CDR VALIDATION (Pydantic V2)")
        logger.info(f"📦 Datos recibidos: {json.dumps(raw_data, indent=2)}")
        
        # Intentar crear CallEvent
        try:
            event = CallEvent.model_validate(raw_data)
            logger.info(f"✅ Validación EXITOSA!")
            logger.info(f"   Calling: {event.calling_number}")
            logger.info(f"   Called: {event.called_number}")
            logger.info(f"   Direction: {event.direction}")
            
            # Crear servicio temporal para detectar dirección
            db = SessionLocal()
            cdr_service = CDRService(db)
            
            calling_is_internal = cdr_service.is_internal_extension(event.calling_number)
            called_is_internal = cdr_service.is_internal_extension(event.called_number)
            detected_direction = cdr_service.detect_call_direction(event.calling_number, event.called_number)
            
            db.close()
            
            return {
                "status": "SUCCESS",
                "message": "Modelo validado correctamente con Pydantic V2",
                "parsed_data": {
                    "calling_number": event.calling_number,
                    "called_number": event.called_number,
                    "direction_original": event.direction,
                    "direction_detectada": detected_direction,
                    "start_time": event.start_time.isoformat() if event.start_time else None,
                    "end_time": event.end_time.isoformat() if event.end_time else None,
                    "answer_time": event.answer_time.isoformat() if event.answer_time else None,
                    "duration_billable": event.duration_billable,
                    "calling_is_internal": calling_is_internal,
                    "called_is_internal": called_is_internal
                }
            }
            
        except Exception as validation_error:
            logger.error(f"❌ Error en validación: {validation_error}")
            return {
                "status": "VALIDATION_ERROR",
                "message": "Error de validación con Pydantic V2",
                "error": str(validation_error),
                "raw_data": raw_data
            }
            
    except Exception as e:
        logger.error(f"❌ Error general: {e}")
        return {
            "status": "ERROR",
            "error": str(e)
        }

@router.post("/test-with-your-data-v2")
async def test_with_your_data_v2():
    """
    Prueba con datos específicos usando Pydantic V2 - Extraído del main.py
    """
    # Datos exactos de tu log
    test_data = {
        "answer_time": "2025-05-31T21:11:19.351733420Z",
        "duration_seconds": 16,
        "calling_number": "983434724",
        "duration_billable": 7,
        "end_time": "2025-05-31T21:11:26.924605727Z",
        "network_alerting_time": None,
        "network_reached_time": None,
        "start_time": "2025-05-31T21:11:10.543149610Z",
        "called_number": "4198",
        "dialing_time": None,
        "release_cause": 0,
        "status": "disconnected",
        "direction": "unknown"
    }
    
    try:
        logger.info("🧪 Probando con datos de tu log usando Pydantic V2...")
        event = CallEvent.model_validate(test_data)
        
        # Crear servicio temporal para análisis
        db = SessionLocal()
        cdr_service = CDRService(db)
        
        calling_is_internal = cdr_service.is_internal_extension(event.calling_number)
        called_is_internal = cdr_service.is_internal_extension(event.called_number)
        detected_direction = cdr_service.detect_call_direction(event.calling_number, event.called_number)
        
        db.close()
        
        return {
            "status": "SUCCESS",
            "message": "Datos de tu log procesados correctamente con Pydantic V2",
            "analysis": {
                "call_type": detected_direction,
                "explanation": f"{event.calling_number} → {event.called_number}",
                "calling_is_internal": calling_is_internal,
                "called_is_internal": called_is_internal,
                "duration_billable": event.duration_billable,
                "would_be_charged": detected_direction == "outbound",
                "cost_calculation": "FREE (inbound call)" if detected_direction == "inbound" else "CALCULATED (outbound call)" if detected_direction == "outbound" else "FREE (internal call)"
            },
            "parsed_timestamps": {
                "start_time": event.start_time.isoformat() if event.start_time else None,
                "end_time": event.end_time.isoformat() if event.end_time else None,
                "answer_time": event.answer_time.isoformat() if event.answer_time else None
            }
        }
        
    except Exception as e:
        return {
            "status": "ERROR",
            "error": str(e),
            "test_data": test_data
        }