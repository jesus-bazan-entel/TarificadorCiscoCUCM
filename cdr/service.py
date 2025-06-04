# cdr/service.py
from sqlalchemy.orm import Session
from sqlalchemy import text, and_, or_
from typing import Optional, Dict, Tuple, List
from datetime import datetime
from collections import Counter
import logging

from .models import CDR, ActiveCall
from .schemas import CallEvent, CDRFilter, CDRResponse, CDRStats, CDRListResponse, ActiveCallRequest
from main import SessionLocal, Zona, Prefijo, Tarifa  # Importar del main.py existente

logger = logging.getLogger(__name__)

class CDRService:
    """Servicio para gestión de CDR - Extraído del main.py"""
    
    def __init__(self, db: Session):
        self.db = db
    
    def create_cdr(self, event: CallEvent) -> Dict:
        """
        Crea un nuevo registro CDR - Función extraída del main.py
        """
        try:
            # 1. Determinar la zona basándose en el prefijo
            zona_id = self.get_zone_by_prefix(event.called_number)
            
            # 2. Obtener la tarifa específica para esa zona
            rate_per_minute = self.get_rate_by_zone(zona_id)
            
            # 3. Calcular el costo basado en duration_billable y tarifa de la zona
            cost = (event.duration_billable / 60) * rate_per_minute
            
            # 4. Detectar dirección si no viene especificada
            if event.direction == "unknown":
                event.direction = self.detect_call_direction(event.calling_number, event.called_number)
            
            # 5. Crear el CDR con la zona determinada automáticamente
            cdr_data = {
                "calling_number": event.calling_number,
                "called_number": event.called_number,
                "start_time": event.start_time,
                "end_time": event.end_time,
                "duration_seconds": event.duration_seconds,
                "duration_billable": event.duration_billable,
                "cost": cost,
                "status": event.status,
                "direction": event.direction,
                "release_cause": event.release_cause,
                "connect_time": event.answer_time,
                "dialing_time": event.dialing_time,
                "network_reached_time": event.network_reached_time,
                "network_alerting_time": event.network_alerting_time,
                "zona_id": zona_id
            }
            
            # 6. Guardar el CDR
            cdr = CDR(**cdr_data)
            self.db.add(cdr)
            
            # 7. Actualizar el saldo del anexo solo si es llamada saliente
            if event.direction == "outbound":
                self.update_anexo_balance(event.calling_number, cost)
            
            # 8. Verificar saldo bajo si existe el anexo
            self.check_low_balance_alert(event.calling_number)
            
            # 9. Confirmar transacción
            self.db.commit()
            
            # 10. Obtener información de la zona para respuesta
            zona_info = self.db.execute(
                text("SELECT nombre FROM zonas WHERE id = :zona_id"),
                {"zona_id": zona_id}
            ).fetchone()
            
            zona_nombre = zona_info[0] if zona_info else "Desconocida"
            
            logger.info(f"CDR creado: {event.calling_number} -> {event.called_number}, "
                       f"Zona: {zona_nombre}, Costo: ${cost:.4f}")
            
            return {
                "message": "CDR saved successfully",
                "cdr_id": cdr.id if hasattr(cdr, 'id') else None,
                "cost": round(cost, 4),
                "zona_id": zona_id,
                "zona_nombre": zona_nombre,
                "rate_per_minute": rate_per_minute,
                "duration_billed_minutes": round(event.duration_billable / 60, 4)
            }
            
        except Exception as e:
            self.db.rollback()
            logger.error(f"Error creando CDR: {e}")
            raise
    
    def get_zone_by_prefix(self, called_number: str) -> int:
        """
        Determina la zona basándose en el prefijo - Extraído del main.py
        """
        if not called_number:
            return 1  # Zona por defecto para números vacíos
        
        # Limpiar el número (quitar espacios, guiones, etc.)
        clean_number = ''.join(filter(str.isdigit, str(called_number)))
        
        if not clean_number:
            return 1  # Zona por defecto si no hay dígitos
        
        try:
            # Obtener todos los prefijos ordenados por longitud descendente
            # Esto prioriza prefijos más específicos (más largos) sobre los generales
            query = text("""
                SELECT zona_id, prefijo, longitud_minima, longitud_maxima
                FROM prefijos 
                ORDER BY LENGTH(prefijo) DESC, prefijo
            """)
            
            prefijos = self.db.execute(query).fetchall()
            
            for prefijo_row in prefijos:
                zona_id, prefijo, long_min, long_max = prefijo_row
                
                # Verificar si el número empieza con este prefijo
                if clean_number.startswith(str(prefijo)):
                    # Verificar longitud del número
                    if long_min and len(clean_number) < long_min:
                        continue
                    if long_max and len(clean_number) > long_max:
                        continue
                    
                    # ¡Encontramos la zona!
                    return zona_id
            
            # Si no se encuentra ningún prefijo, usar zona por defecto
            logger.warning(f"No se encontró zona para el número: {called_number}")
            return 1  # Zona por defecto
            
        except Exception as e:
            logger.error(f"Error determinando zona para {called_number}: {e}")
            return 1  # Zona por defecto en caso de error
    
    def get_rate_by_zone(self, zona_id: int) -> float:
        """
        Obtiene la tarifa por minuto para una zona específica - Extraído del main.py
        """
        try:
            query = text("""
                SELECT tarifa_segundo 
                FROM tarifas 
                WHERE zona_id = :zona_id 
                  AND activa = true
                ORDER BY fecha_inicio DESC
                LIMIT 1
            """)
            
            result = self.db.execute(query, {"zona_id": zona_id}).fetchone()
            
            if result and result[0] is not None:
                # Convertir de tarifa por segundo a tarifa por minuto
                tarifa_por_segundo = float(result[0])
                tarifa_por_minuto = tarifa_por_segundo * 60
                return tarifa_por_minuto
            else:
                logger.warning(f"No se encontró tarifa activa para zona {zona_id}")
                return 3.0  # Tarifa por defecto: 3.0 por minuto
                
        except Exception as e:
            logger.error(f"Error obteniendo tarifa para zona {zona_id}: {e}")
            return 3.0  # Tarifa por defecto en caso de error
    
    def detect_call_direction(self, calling_number: str, called_number: str) -> str:
        """Detecta la dirección de la llamada - Lógica extraída del main.py"""
        calling_is_internal = self.is_internal_extension(calling_number)
        called_is_internal = self.is_internal_extension(called_number)
        
        if calling_is_internal and not called_is_internal:
            return "outbound"
        elif not calling_is_internal and called_is_internal:
            return "inbound"
        elif calling_is_internal and called_is_internal:
            return "internal"
        else:
            return "transit"
    
    def is_internal_extension(self, number: str) -> bool:
        """Determina si un número es un anexo interno - Extraído del main.py"""
        if not number:
            return False
        
        clean_number = ''.join(filter(str.isdigit, str(number)))
        
        if len(clean_number) == 4 and clean_number[0] in ['3', '4', '5']:
            return True
        
        try:
            num = int(clean_number)
            if 3000 <= num <= 5999:
                return True
        except:
            pass
        
        return False
    
    def update_anexo_balance(self, calling_number: str, cost: float):
        """Actualiza el saldo del anexo - Extraído del main.py"""
        try:
            update_result = self.db.execute(
                text("UPDATE saldo_anexos SET saldo = saldo - :cost WHERE calling_number = :calling_number"),
                {"cost": cost, "calling_number": calling_number}
            )
            
            if update_result.rowcount == 0:
                logger.warning(f"Anexo {calling_number} no encontrado en saldo_anexos")
        except Exception as e:
            logger.error(f"Error actualizando saldo para {calling_number}: {e}")
    
    def check_low_balance_alert(self, calling_number: str):
        """Verifica saldo bajo y genera alerta - Extraído del main.py"""
        try:
            nuevo_saldo_result = self.db.execute(
                text("SELECT saldo FROM saldo_anexos WHERE calling_number = :calling_number"),
                {"calling_number": calling_number}
            ).fetchone()
            
            if nuevo_saldo_result:
                nuevo_saldo = nuevo_saldo_result[0]
                if nuevo_saldo < 1.0:
                    logger.warning(f"ALERTA: Anexo {calling_number} con saldo bajo: ${nuevo_saldo:.2f}")
        except Exception as e:
            logger.error(f"Error verificando saldo bajo: {e}")
    
    def get_cdr_list(self, filters: CDRFilter) -> CDRListResponse:
        """Obtiene lista filtrada de CDR - Extraído y mejorado del main.py"""
        try:
            # Construir query base
            query = text("""
                SELECT 
                    c.id, c.calling_number, c.called_number, c.start_time, c.end_time,
                    c.duration_seconds, c.duration_billable, c.cost, c.status, c.direction,
                    c.release_cause, c.zona_id, z.nombre as zona_nombre
                FROM cdr c
                LEFT JOIN zonas z ON c.zona_id = z.id
                WHERE 1=1
            """)
            
            # Aplicar filtros
            params = {}
            conditions = []
            
            if filters.phone_number:
                conditions.append("(c.calling_number LIKE :phone_pattern OR c.called_number LIKE :phone_pattern)")
                params['phone_pattern'] = f"%{filters.phone_number}%"
            
            if filters.calling_number:
                conditions.append("c.calling_number = :calling_number")
                params['calling_number'] = filters.calling_number
            
            if filters.called_number:
                conditions.append("c.called_number = :called_number")
                params['called_number'] = filters.called_number
            
            if filters.start_date:
                conditions.append("c.start_time >= :start_date")
                params['start_date'] = f"{filters.start_date} 00:00:00"
            
            if filters.end_date:
                conditions.append("c.start_time <= :end_date")
                params['end_date'] = f"{filters.end_date} 23:59:59"
            
            if filters.min_duration and filters.min_duration > 0:
                conditions.append("COALESCE(c.duration_seconds, 0) >= :min_duration")
                params['min_duration'] = filters.min_duration
            
            if filters.status and filters.status != 'all':
                conditions.append("COALESCE(c.status, 'unknown') = :status")
                params['status'] = filters.status
            
            if filters.direction and filters.direction != 'all':
                conditions.append("COALESCE(c.direction, 'unknown') = :direction")
                params['direction'] = filters.direction
            
            # Agregar condiciones a la query
            if conditions:
                query_str = str(query) + " AND " + " AND ".join(conditions)
            else:
                query_str = str(query)
            
            # Contar total de registros
            count_query = f"SELECT COUNT(*) FROM ({query_str}) as count_query"
            total_records = self.db.execute(text(count_query), params).scalar()
            total_pages = (total_records + filters.per_page - 1) // filters.per_page
            
            # Agregar ordenamiento y paginación
            offset = (filters.page - 1) * filters.per_page
            query_str += f" ORDER BY c.start_time DESC LIMIT {filters.per_page} OFFSET {offset}"
            
            # Ejecutar query principal
            rows = self.db.execute(text(query_str), params).fetchall()
            
            # Procesar resultados
            records = []
            for row in rows:
                # Procesar status para mostrar
                call_type = self.process_cdr_status(row[8], row[5], row[6])  # status, duration_seconds, duration_billable
                
                # Formatear dirección
                direction_display = {
                    'inbound': '📱 Entrante',
                    'outbound': '📞 Saliente', 
                    'internal': '🏢 Interna',
                    'transit': '🔄 Tránsito',
                    'unknown': '❓ Desconocida'
                }.get(row[9] or 'unknown', '❓ Desconocida')  # direction
                
                record = CDRResponse(
                    id=row[0],
                    calling_number=row[1],
                    called_number=row[2],
                    start_time=row[3],
                    end_time=row[4],
                    duration_seconds=row[5] or 0,
                    duration_billable=row[6] or 0,
                    cost=float(row[7]) if row[7] else 0.0,
                    status=row[8] or 'unknown',
                    direction=row[9] or 'unknown',
                    zone_name=row[12],  # zona_nombre
                    call_type=call_type,
                    direction_display=direction_display
                )
                records.append(record)
            
            # Calcular estadísticas
            stats = self.calculate_stats(records)
            
            return CDRListResponse(
                records=records,
                total_records=total_records,
                total_pages=total_pages,
                current_page=filters.page,
                stats=stats,
                filters_applied=filters
            )
            
        except Exception as e:
            logger.error(f"Error obteniendo lista CDR: {e}")
            raise
    
    def process_cdr_status(self, status: str, duration_seconds: int, duration_billable: int) -> str:
        """Procesa el estado de la llamada - Extraído del main.py"""
        if status == 'no_answer':
            return "📵 No contestada"
        elif status == 'failed':
            return "❌ Fallida"
        elif status == 'answered' or status == 'completed':
            return "✅ Completada"
        elif status == 'ringing' or status == 'alerting':
            return "🔔 Timbrando"
        elif status == 'dialing':
            return "📞 Marcando"
        elif status == 'in_progress':
            return "🔄 En progreso"
        elif status == 'disconnected':
            if duration_billable and duration_billable > 0:
                return "✅ Completada"
            else:
                return "📵 No contestada"
        else:
            if duration_billable and duration_billable > 0:
                return f"✅ Completada ({status})"
            else:
                return f"❓ {status.title() if status else 'Desconocido'}"
    
    def calculate_stats(self, records: List[CDRResponse]) -> CDRStats:
        """Calcula estadísticas para los registros CDR"""
        total_calls = len(records)
        completed_calls = sum(1 for r in records if r.status == 'disconnected' and r.duration_seconds > 0)
        failed_calls = sum(1 for r in records if r.status == 'failed')
        unanswered_calls = sum(1 for r in records if r.status == 'disconnected' and r.duration_seconds == 0)
        total_cost = sum(r.cost for r in records)
        total_duration = sum(r.duration_seconds for r in records)
        
        return CDRStats(
            total_calls=total_calls,
            completed_calls=completed_calls,
            failed_calls=failed_calls,
            unanswered_calls=unanswered_calls,
            total_cost=total_cost,
            total_duration=total_duration
        )
    
    def get_call_stats_by_hour(self, start_date: Optional[str] = None, end_date: Optional[str] = None) -> Dict:
        """Obtiene estadísticas de llamadas por hora"""
        try:
            conditions = []
            params = {}
            
            if start_date:
                conditions.append("start_time >= :start_date")
                params['start_date'] = f"{start_date} 00:00:00"
            if end_date:
                conditions.append("start_time <= :end_date")
                params['end_date'] = f"{end_date} 23:59:59"
            
            where_clause = " WHERE " + " AND ".join(conditions) if conditions else ""
            
            query = text(f"SELECT * FROM cdr{where_clause}")
            records = self.db.execute(query, params).fetchall()
            
            # Agrupar por hora
            hour_stats = {}
            for record in records:
                if record[3]:  # start_time está en posición 3
                    hour = record[3].hour
                    if hour not in hour_stats:
                        hour_stats[hour] = 0
                    hour_stats[hour] += 1
            
            # Convertir a listas ordenadas
            hours = sorted(hour_stats.keys())
            counts = [hour_stats[h] for h in hours]
            
            return {
                "labels": hours,
                "data": counts
            }
        except Exception as e:
            logger.error(f"Error obteniendo estadísticas por hora: {e}")
            return {"labels": [], "data": []}

    # === MÉTODOS PARA LLAMADAS ACTIVAS ===
    
    def report_active_call(self, call_data: ActiveCallRequest) -> Dict:
        """Reporta una llamada activa - Extraído del main.py"""
        try:
            logger.info(f"Recibido reporte de llamada activa: {call_data.call_id}")
            
            # Mapear campos para la BD
            db_call_data = {
                "call_id": call_data.call_id,
                "calling_number": call_data.calling_number,
                "called_number": call_data.called_number,
                "direction": call_data.direction,
                "zone": call_data.zone,
                "start_time": datetime.fromisoformat(call_data.start_time.replace('Z', '+00:00')) 
                    if call_data.start_time else datetime.now(),
                "last_updated": datetime.now(),
                "current_duration": call_data.current_duration,
                "current_cost": call_data.current_cost,
                "connection_id": call_data.connection_id or call_data.call_id
            }
            
            # Verificar si ya existe
            existing = self.db.execute(
                text("SELECT id FROM active_calls WHERE call_id = :call_id"),
                {"call_id": call_data.call_id}
            ).fetchone()
            
            if existing:
                # Actualizar
                set_clause = ", ".join([f"{k} = :{k}" for k in db_call_data.keys()])
                query = text(f"UPDATE active_calls SET {set_clause} WHERE call_id = :call_id")
                self.db.execute(query, db_call_data)
            else:
                # Insertar nuevo
                columns = ", ".join(db_call_data.keys())
                placeholders = ", ".join([f":{k}" for k in db_call_data.keys()])
                query = text(f"INSERT INTO active_calls ({columns}) VALUES ({placeholders})")
                self.db.execute(query, db_call_data)
            
            self.db.commit()
            
            logger.info(f"Llamada activa actualizada: {call_data.call_id}")
            return {"status": "ok", "message": "Llamada activa reportada"}
            
        except Exception as e:
            self.db.rollback()
            logger.error(f"Error reportando llamada activa: {e}")
            raise
    
    def remove_active_call(self, call_id: str) -> Dict:
        """Elimina una llamada activa - Extraído del main.py"""
        try:
            # Buscar primero por call_id
            result = self.db.execute(
                text("SELECT id FROM active_calls WHERE call_id = :call_id"),
                {"call_id": call_id}
            ).fetchone()
            
            # Si no se encuentra, buscar por connection_id
            if not result:
                result = self.db.execute(
                    text("SELECT id FROM active_calls WHERE connection_id = :call_id"),
                    {"call_id": call_id}
                ).fetchone()
            
            if result:
                # Eliminar la llamada
                self.db.execute(
                    text("DELETE FROM active_calls WHERE id = :id"),
                    {"id": result[0]}
                )
                self.db.commit()
                
                logger.info(f"Llamada activa eliminada: {call_id}")
                return {"status": "ok", "message": f"Llamada {call_id} eliminada"}
            else:
                logger.warning(f"Llamada activa no encontrada: {call_id}")
                return {"status": "not_found", "message": f"Llamada {call_id} no encontrada"}
                
        except Exception as e:
            self.db.rollback()
            logger.error(f"Error eliminando llamada activa: {e}")
            raise
    
    def get_active_calls(self) -> List[Dict]:
        """Obtiene todas las llamadas activas - Extraído del main.py"""
        try:
            active_calls_rows = self.db.execute(
                text("""SELECT call_id, calling_number, called_number, direction, start_time, 
                current_duration, current_cost, zone
                FROM active_calls ORDER BY start_time DESC""")
            ).fetchall()
            
            active_calls = []
            for row in active_calls_rows:
                direction = row[3] if len(row) > 3 and row[3] else "unknown"
                
                # Crear display version con iconos
                direction_displays = {
                    "inbound": "📱 Entrante",
                    "outbound": "📞 Saliente", 
                    "internal": "🏢 Interna",
                    "transit": "🔄 Tránsito"
                }
                direction_display = direction_displays.get(direction, f"❓ {direction}")
                
                call = {
                    "call_id": row[0],
                    "calling_number": row[1],
                    "called_number": row[2],
                    "direction": direction,
                    "direction_display": direction_display,
                    "start_time": row[4].isoformat() if row[4] else None,
                    "current_duration": row[5] if row[5] is not None else 0,
                    "current_cost": float(row[6]) if row[6] is not None else 0.0,
                    "zone": row[7] if len(row) > 7 else "Desconocida"
                }
                active_calls.append(call)
            
            return active_calls
            
        except Exception as e:
            logger.error(f"Error obteniendo llamadas activas: {e}")
            return []