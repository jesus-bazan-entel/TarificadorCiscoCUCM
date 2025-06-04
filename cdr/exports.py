# cdr/exports.py
from fastapi import APIRouter, Depends, Query, HTTPException
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session
from typing import Optional
from jinja2 import Template
from weasyprint import HTML
import pandas as pd
import io
from datetime import datetime
import logging

from .service import CDRService
from .schemas import CDRFilter
from main import SessionLocal, admin_only  # Importar del main.py existente

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/export", tags=["Exportaciones CDR"])

@router.get("/cdr/pdf")
def export_cdr_pdf(
    phone_number: Optional[str] = Query(None, description="Buscar en origen o destino"),
    calling_number: Optional[str] = Query(None, description="Número origen específico"),
    called_number: Optional[str] = Query(None, description="Número destino específico"),
    start_date: Optional[str] = Query(None, description="Fecha inicio (YYYY-MM-DD)"),
    end_date: Optional[str] = Query(None, description="Fecha fin (YYYY-MM-DD)"),
    status: Optional[str] = Query(None, description="Estado de la llamada"),
    direction: Optional[str] = Query(None, description="Dirección de la llamada"),
    user=Depends(admin_only)
):
    """
    Exporta registros CDR a PDF con filtros aplicados - Extraído del main.py
    """
    db = SessionLocal()
    
    try:
        # Crear filtros
        filters = CDRFilter(
            phone_number=phone_number,
            calling_number=calling_number,
            called_number=called_number,
            start_date=start_date,
            end_date=end_date,
            status=status,
            direction=direction,
            page=1,
            per_page=1000  # Límite alto para exportación
        )
        
        # Obtener datos
        cdr_service = CDRService(db)
        summary = cdr_service.get_cdr_list(filters)
        
        logger.info(f"Exportando {len(summary.records)} registros a PDF")
        
        # Generar HTML usando el template extraído del main.py
        html_content = generate_pdf_template(summary.records, filters)
        
        # Convertir a PDF
        pdf = HTML(string=html_content).write_pdf()
        
        # Generar nombre de archivo
        filename = generate_filename("pdf", filters)
        
        logger.info(f"PDF de CDR exportado: {filename}")
        
        return StreamingResponse(
            io.BytesIO(pdf),
            media_type="application/pdf",
            headers={"Content-Disposition": f"attachment; filename={filename}"}
        )
        
    except Exception as e:
        logger.error(f"Error exportando CDR a PDF: {e}")
        raise HTTPException(status_code=500, detail=f"Error generando PDF: {str(e)}")
    finally:
        db.close()

@router.get("/cdr/excel")
def export_cdr_excel(
    phone_number: Optional[str] = Query(None, description="Buscar en origen o destino"),
    calling_number: Optional[str] = Query(None, description="Número origen específico"),
    called_number: Optional[str] = Query(None, description="Número destino específico"),
    start_date: Optional[str] = Query(None, description="Fecha inicio (YYYY-MM-DD)"),
    end_date: Optional[str] = Query(None, description="Fecha fin (YYYY-MM-DD)"),
    status: Optional[str] = Query(None, description="Estado de la llamada"),
    direction: Optional[str] = Query(None, description="Dirección de la llamada"),
    user=Depends(admin_only)
):
    """
    Exporta registros CDR a Excel con múltiples hojas - Extraído del main.py
    """
    db = SessionLocal()
    
    try:
        # Crear filtros
        filters = CDRFilter(
            phone_number=phone_number,
            calling_number=calling_number,
            called_number=called_number,
            start_date=start_date,
            end_date=end_date,
            status=status,
            direction=direction,
            page=1,
            per_page=1000  # Límite alto para exportación
        )
        
        # Obtener datos
        cdr_service = CDRService(db)
        summary = cdr_service.get_cdr_list(filters)
        
        logger.info(f"Exportando {len(summary.records)} registros a Excel")
        
        # Crear DataFrame principal
        df_data = []
        for record in summary.records:
            duration_min = round(record.duration_seconds/60, 2) if record.duration_seconds else 0
            billable_min = round(record.duration_billable/60, 2) if record.duration_billable else 0
            
            df_data.append({
                'ID': record.id,
                'Número Origen': record.calling_number,
                'Número Destino': record.called_number,
                'Fecha Inicio': record.start_time.strftime('%Y-%m-%d %H:%M:%S') if record.start_time else '',
                'Fecha Fin': record.end_time.strftime('%Y-%m-%d %H:%M:%S') if record.end_time else '',
                'Duración (seg)': record.duration_seconds,
                'Duración (min)': duration_min,
                'Facturable (seg)': record.duration_billable,
                'Facturable (min)': billable_min,
                'Costo': record.cost,
                'Estado': record.status,
                'Dirección': record.direction,
                'Zona': record.zone_name or 'N/A'
            })
        
        df_main = pd.DataFrame(df_data)
        
        # Crear buffer y escribir Excel
        buffer = io.BytesIO()
        with pd.ExcelWriter(buffer, engine='openpyxl') as writer:
            # Hoja principal con datos
            df_main.to_excel(writer, sheet_name='CDR_Data', index=False)
            
            # Hoja de resumen con filtros aplicados
            summary_data = create_summary_sheet(filters, summary, len(df_data))
            df_summary = pd.DataFrame(summary_data)
            df_summary.to_excel(writer, sheet_name='Resumen', index=False)
            
            # Hoja de estadísticas
            stats_data = create_stats_sheet(summary.stats)
            df_stats = pd.DataFrame(stats_data)
            df_stats.to_excel(writer, sheet_name='Estadisticas', index=False)
        
        buffer.seek(0)
        
        # Generar nombre de archivo
        filename = generate_filename("excel", filters)
        
        logger.info(f"Excel de CDR exportado: {filename}")
        
        return StreamingResponse(
            io.BytesIO(buffer.getvalue()),
            media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            headers={"Content-Disposition": f"attachment; filename={filename}"}
        )
        
    except Exception as e:
        logger.error(f"Error exportando CDR a Excel: {e}")
        raise HTTPException(status_code=500, detail=f"Error generando Excel: {str(e)}")
    finally:
        db.close()

def generate_pdf_template(records: list, filters: CDRFilter) -> str:
    """
    Genera el template HTML para el PDF de CDR - Extraído del main.py
    """
    html_template = """
    <html>
    <head>
        <style>
            body { font-family: Arial, sans-serif; margin: 20px; font-size: 12px; }
            table { width: 100%; border-collapse: collapse; margin-top: 20px; }
            th, td { border: 1px solid #ddd; padding: 6px; text-align: left; font-size: 10px; }
            th { background-color: #f2f2f2; font-weight: bold; }
            .header { text-align: center; margin-bottom: 20px; }
            .filters { background-color: #f9f9f9; padding: 10px; margin-bottom: 20px; border-radius: 5px; }
            .footer { text-align: center; margin-top: 20px; font-size: 0.8em; color: #666; }
            h1 { color: #333; margin-bottom: 10px; }
            .filter-item { display: inline-block; margin-right: 15px; font-size: 0.9em; }
        </style>
    </head>
    <body>
        <div class="header">
            <h1>📞 Registro de Llamadas (CDR)</h1>
            <p><strong>Total de registros:</strong> {{ records|length }}</p>
            <p><strong>Generado:</strong> {{ datetime.now().strftime('%Y-%m-%d %H:%M:%S') }}</p>
        </div>
        
        {% if has_filters %}
        <div class="filters">
            <strong>🔍 Filtros aplicados:</strong><br/>
            {% if filters.phone_number %}
            <span class="filter-item">📞 <strong>Número:</strong> {{ filters.phone_number }}</span>
            {% endif %}
            {% if filters.start_date %}
            <span class="filter-item">📅 <strong>Desde:</strong> {{ filters.start_date }}</span>
            {% endif %}
            {% if filters.end_date %}
            <span class="filter-item">📅 <strong>Hasta:</strong> {{ filters.end_date }}</span>
            {% endif %}
            {% if filters.status %}
            <span class="filter-item">📊 <strong>Estado:</strong> {{ filters.status }}</span>
            {% endif %}
            {% if filters.direction %}
            <span class="filter-item">🔄 <strong>Dirección:</strong> {{ filters.direction }}</span>
            {% endif %}
        </div>
        {% endif %}
        
        {% if records %}
        <table>
            <thead>
                <tr>
                    <th>📞 Origen</th>
                    <th>📱 Destino</th>
                    <th>📅 Fecha/Hora</th>
                    <th>⏱️ Duración</th>
                    <th>💰 Facturable</th>
                    <th>🌍 Zona</th>
                    <th>💵 Costo</th>
                    <th>📊 Estado</th>
                    <th>🔄 Dirección</th>
                </tr>
            </thead>
            <tbody>
            {% for record in records %}
                <tr>
                    <td>{{ record.calling_number }}</td>
                    <td>{{ record.called_number }}</td>
                    <td>{{ record.start_time.strftime('%Y-%m-%d %H:%M:%S') if record.start_time else 'N/A' }}</td>
                    <td>{{ "%d:%02d"|format(record.duration_seconds//60, record.duration_seconds%60) }}</td>
                    <td>{{ "%d:%02d"|format(record.duration_billable//60, record.duration_billable%60) }}</td>
                    <td>{{ record.zone_name or 'N/A' }}</td>
                    <td>S/ {{ "%.4f"|format(record.cost) }}</td>
                    <td>{{ record.status or 'N/A' }}</td>
                    <td>{{ record.direction or 'N/A' }}</td>
                </tr>
            {% endfor %}
            </tbody>
        </table>
        {% else %}
        <div style="text-align: center; padding: 40px; color: #666;">
            <h3>📭 No se encontraron registros</h3>
            <p>No hay llamadas que coincidan con los filtros aplicados.</p>
        </div>
        {% endif %}
        
        <div class="footer">
            <p>📄 Reporte generado el {{ datetime.now().strftime('%Y-%m-%d %H:%M:%S') }} | 
               📊 Total: {{ records|length }} registro{{ 's' if records|length != 1 else '' }}</p>
        </div>
    </body>
    </html>
    """
    
    # Verificar si hay filtros aplicados
    has_filters = any([
        filters.phone_number,
        filters.start_date,
        filters.end_date,
        filters.status,
        filters.direction,
        filters.calling_number,
        filters.called_number
    ])
    
    template = Template(html_template)
    return template.render(
        records=records,
        filters=filters,
        has_filters=has_filters,
        datetime=datetime
    )

def create_summary_sheet(filters: CDRFilter, summary, record_count: int) -> list:
    """
    Crea datos para la hoja de resumen del Excel - Extraído del main.py
    """
    summary_data = []
    
    # Información de filtros
    if filters.phone_number:
        summary_data.append({'Filtro': 'Número', 'Valor': filters.phone_number})
    if filters.start_date:
        summary_data.append({'Filtro': 'Fecha Inicio', 'Valor': filters.start_date})
    if filters.end_date:
        summary_data.append({'Filtro': 'Fecha Fin', 'Valor': filters.end_date})
    if filters.status:
        summary_data.append({'Filtro': 'Estado', 'Valor': filters.status})
    if filters.direction:
        summary_data.append({'Filtro': 'Dirección', 'Valor': filters.direction})
    
    # Información del reporte
    summary_data.extend([
        {'Filtro': 'Total Registros', 'Valor': record_count},
        {'Filtro': 'Fecha Generación', 'Valor': datetime.now().strftime('%Y-%m-%d %H:%M:%S')},
        {'Filtro': 'Generado por', 'Valor': 'Sistema Tarificador'}
    ])
    
    return summary_data

def create_stats_sheet(stats) -> list:
    """
    Crea datos para la hoja de estadísticas del Excel - Extraído del main.py
    """
    return [
        {'Métrica': 'Total de Llamadas', 'Valor': stats.total_calls},
        {'Métrica': 'Llamadas Completadas', 'Valor': stats.completed_calls},
        {'Métrica': 'Llamadas Fallidas', 'Valor': stats.failed_calls},
        {'Métrica': 'Llamadas No Contestadas', 'Valor': stats.unanswered_calls},
        {'Métrica': 'Costo Total', 'Valor': f"S/ {stats.total_cost:.2f}"},
        {'Métrica': 'Duración Total (segundos)', 'Valor': stats.total_duration},
        {'Métrica': 'Duración Total (minutos)', 'Valor': round(stats.total_duration / 60, 2)},
        {'Métrica': 'Costo Promedio', 'Valor': f"S/ {stats.total_cost / stats.total_calls:.4f}" if stats.total_calls > 0 else "S/ 0.00"}
    ]

def generate_filename(export_type: str, filters: CDRFilter) -> str:
    """
    Genera nombre de archivo basado en filtros y tipo - Extraído del main.py
    """
    filename_parts = ["cdr_report"]
    
    if filters.phone_number:
        filename_parts.append(f"num_{filters.phone_number}")
    if filters.status:
        filename_parts.append(f"status_{filters.status}")
    if filters.direction:
        filename_parts.append(f"dir_{filters.direction}")
    if filters.start_date:
        filename_parts.append(f"desde_{filters.start_date}")
    if filters.end_date:
        filename_parts.append(f"hasta_{filters.end_date}")
    
    filename_parts.append(datetime.now().strftime('%Y%m%d_%H%M%S'))
    
    extension = "pdf" if export_type == "pdf" else "xlsx"
    return "_".join(filename_parts) + f".{extension}"