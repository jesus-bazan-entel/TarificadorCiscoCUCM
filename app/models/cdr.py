from sqlalchemy import Column, Integer, String, Numeric, DateTime, Boolean
from sqlalchemy.ext.declarative import declarative_base
from datetime import datetime

Base = declarative_base()

class CDR(Base):
    """Modelo para registros de detalle de llamadas"""
    __tablename__ = "cdr"
    
    id = Column(Integer, primary_key=True, index=True)
    calling_number = Column(String, index=True, comment="Número que origina la llamada")
    called_number = Column(String, index=True, comment="Número de destino")
    start_time = Column(DateTime, index=True, comment="Tiempo de inicio de la llamada")
    end_time = Column(DateTime, comment="Tiempo de fin de la llamada")
    duration_seconds = Column(Integer, default=0, comment="Duración total en segundos")
    duration_billable = Column(Integer, default=0, comment="Duración facturable en segundos")
    cost = Column(Numeric(10,2), default=0, comment="Costo de la llamada")
    status = Column(String, default="unknown", comment="Estado de la llamada")
    direction = Column(String, default="unknown", comment="Dirección: inbound/outbound/internal")
    release_cause = Column(Integer, default=0, comment="Código de liberación")
    connect_time = Column(DateTime, comment="Tiempo de conexión (answer)")
    dialing_time = Column(DateTime, comment="Tiempo de marcado")
    network_reached_time = Column(DateTime, comment="Tiempo cuando se alcanza la red")
    network_alerting_time = Column(DateTime, comment="Tiempo de alerting en red")
    zona_id = Column(Integer, comment="ID de la zona de tarifación")
    
    def __repr__(self):
        return f"<CDR {self.calling_number} -> {self.called_number} ({self.duration_seconds}s)>"
    
    def to_dict(self):
        """Convierte el objeto a diccionario"""
        return {
            'id': self.id,
            'calling_number': self.calling_number,
            'called_number': self.called_number,
            'start_time': self.start_time.isoformat() if self.start_time else None,
            'end_time': self.end_time.isoformat() if self.end_time else None,
            'duration_seconds': self.duration_seconds,
            'duration_billable': self.duration_billable,
            'cost': float(self.cost) if self.cost else 0.0,
            'status': self.status,
            'direction': self.direction,
            'release_cause': self.release_cause,
            'zona_id': self.zona_id
        }

class ActiveCall(Base):
    """Modelo para llamadas activas en tiempo real"""
    __tablename__ = "active_calls"
    
    id = Column(Integer, primary_key=True, index=True)
    call_id = Column(String, unique=True, index=True, comment="ID único de la llamada")
    calling_number = Column(String, comment="Número origen")
    called_number = Column(String, comment="Número destino")
    direction = Column(String, default="unknown", comment="Dirección de la llamada")
    start_time = Column(DateTime, default=datetime.utcnow, comment="Inicio de la llamada")
    last_updated = Column(DateTime, default=datetime.utcnow, comment="Última actualización")
    current_duration = Column(Integer, default=0, comment="Duración actual en segundos")
    current_cost = Column(Numeric(10,2), default=0, comment="Costo actual")
    zone = Column(String, comment="Zona de la llamada")
    connection_id = Column(String, comment="ID de conexión del sistema")
    
    def __repr__(self):
        return f"<ActiveCall {self.calling_number} -> {self.called_number}>"
    
    def to_dict(self):
        return {
            'id': self.id,
            'call_id': self.call_id,
            'calling_number': self.calling_number,
            'called_number': self.called_number,
            'direction': self.direction,
            'start_time': self.start_time.isoformat() if self.start_time else None,
            'current_duration': self.current_duration,
            'current_cost': float(self.current_cost) if self.current_cost else 0.0,
            'zone': self.zone
        }