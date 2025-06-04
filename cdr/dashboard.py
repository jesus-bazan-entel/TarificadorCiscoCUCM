# cdr/dashboard.py
from fastapi import APIRouter, Depends, Request, Query
from fastapi.templating import Jinja2Templates
from sqlalchemy.orm import Session
from typing import Optional
from collections import Counter
import logging

from .service import CDRService
from .schemas import CDRFilter
from main import SessionLocal, authenticated_user  # Importar del main.py existente

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/dashboard", tags=["Dashboard CDR"])
templates = Jinja2Templates(directory="templates")

@router.get("/cdr")
def dashboard_cdr(
    request: Request,
    user=Depends(authenticated_user),
    page: int = Query(1, ge=1),
    per_page: int = Query(10, ge=1, le=100),
    phone_number: str = Query(None),
    start_date: str = Query(None),
    end_date: str = Query(None),
    min_duration: int = Query(0),
    status: str = Query(None),
    direction: str = Query(None)
):
    """
    Dashboard de registros CDR con filtros y paginación - Extraído del main.py
    """
    db = SessionLocal()
    
    try:
        # Crear filtros
        filters = CDRFilter(
            phone_number=phone_number,
            start_date=start_date,
            end_date=end_date,
            min_duration=min_duration,
            status=status,
            direction=direction,
            page=page,
            per_page=per_page
        )
        
        # Obtener datos usando el servicio CDR
        cdr_service = CDRService(db)
        summary = cdr_service.get_cdr_list(filters)
        
        # Procesar datos para el template - mantener compatibilidad con template existente
        processed_rows = []
        for record in summary.records:
            # Convertir CDRResponse a formato que espera el template
            processed_row = [
                record.calling_number,           # 0
                record.called_number,            # 1
                record.start_time,               # 2
                record.end_time,                 # 3
                record.duration_seconds,         # 4
                record.cost,                     # 5
                None,                           # 6 - hangup_cause (no usado)
                record.status,                   # 7
                record.direction,                # 8
                None,                           # 9 - cdr_id (no usado)
                record.duration_billable,        # 10
                record.call_type,                # 11 - call_type procesado
                record.direction_display         # 12 - direction_display
            ]
            processed_rows.append(processed_row)
        
        # Estadísticas para gráficos - extraído del main.py
        chart_data = calculate_chart_data(processed_rows)
        
        return templates.TemplateResponse("dashboard_cdr.html", {
            "request": request,
            "rows": processed_rows,
            "page": page,
            "per_page": per_page,
            "total_pages": summary.total_pages,
            "total_records": summary.total_records,
            "stats": {
                "total_calls": summary.stats.total_calls,
                "completed_calls": summary.stats.completed_calls,
                "failed_calls": summary.stats.failed_calls,
                "unanswered_calls": summary.stats.unanswered_calls,
                "total_cost": summary.stats.total_cost,
                "total_duration": summary.stats.total_duration
            },
            "user": user,
            "current_filters": {
                "phone_number": phone_number,
                "start_date": start_date,
                "end_date": end_date,
                "min_duration": min_duration,
                "status": status,
                "direction": direction
            },
            **chart_data
        })
        
    except Exception as e:
        logger.error(f"Error en dashboard CDR: {e}")
        return templates.TemplateResponse("error.html", {
            "request": request,
            "error": str(e),
            "user": user
        })
    finally:
        db.close()

def calculate_chart_data(rows: list) -> dict:
    """
    Calcula datos para gráficos del dashboard CDR - Función extraída del main.py
    """
    if not rows:
        return {
            "labels": [],
            "data": [],
            "status_labels": [],
            "status_data": [],
            "direction_labels": [],
            "direction_data": []
        }
    
    # Gráfico por horas
    horas = [row[2].hour for row in rows if row[2]]  # start_time está en posición 2
    contador_horas = Counter(horas)
    labels_horas = sorted(contador_horas.keys())
    data_horas = [contador_horas[h] for h in labels_horas]
    
    # Gráfico por status
    status_counts = Counter([row[7] for row in rows])  # status está en posición 7
    status_labels = list(status_counts.keys())
    status_data = list(status_counts.values())
    
    # Gráfico por dirección
    direction_counts = Counter([row[8] for row in rows])  # direction está en posición 8
    direction_labels = list(direction_counts.keys())
    direction_data = list(direction_counts.values())
    
    return {
        "labels": labels_horas,
        "data": data_horas,
        "status_labels": status_labels,
        "status_data": status_data,
        "direction_labels": direction_labels,
        "direction_data": direction_data
    }

@router.get("/cdr/stats")
def dashboard_cdr_stats(
    request: Request,
    user=Depends(authenticated_user),
    start_date: Optional[str] = Query(None),
    end_date: Optional[str] = Query(None)
):
    """
    Endpoint para obtener estadísticas de CDR en formato JSON
    """
    db = SessionLocal()
    
    try:
        cdr_service = CDRService(db)
        hourly_stats = cdr_service.get_call_stats_by_hour(start_date, end_date)
        
        return {
            "hourly_stats": hourly_stats,
            "timestamp": "now"
        }
    except Exception as e:
        logger.error(f"Error obteniendo estadísticas CDR: {e}")
        return {"error": str(e)}
    finally:
        db.close()

@router.get("/cdr/realtime")
def dashboard_cdr_realtime(request: Request, user=Depends(authenticated_user)):
    """
    Dashboard de llamadas en tiempo real - Funcionalidad extraída del main.py
    """
    db = SessionLocal()
    
    try:
        cdr_service = CDRService(db)
        active_calls = cdr_service.get_active_calls()
        
        return templates.TemplateResponse("dashboard_cdr_realtime.html", {
            "request": request,
            "active_calls": active_calls,
            "user": user
        })
    except Exception as e:
        logger.error(f"Error en dashboard tiempo real: {e}")
        return templates.TemplateResponse("error.html", {
            "request": request,
            "error": str(e),
            "user": user
        })
    finally:
        db.close()