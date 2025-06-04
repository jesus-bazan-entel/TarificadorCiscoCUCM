# cdr/models.py
from sqlalchemy import Column, Integer, String, Numeric, DateTime, Boolean
from datetime import datetime
from main import Base  # Importar Base del main.py existente

class CDR(Base):
    """Modelo CDR extraído del main.py"""
    __tablename__ = "cdr"
    
    id = Column(Integer, primary_key=True, index=True)
    calling_number = Column(String)
    called_number = Column(String)
    start_time = Column(DateTime)
    end_time = Column(DateTime)
    duration_seconds = Column(Integer)
    duration_billable = Column(Integer)
    cost = Column(Numeric(10,2))
    status = Column(String)
    direction = Column(String)
    release_cause = Column(Integer)
    connect_time = Column(DateTime)
    dialing_time = Column(DateTime)
    network_reached_time = Column(DateTime)
    network_alerting_time = Column(DateTime)
    zona_id = Column(Integer)
    
    def to_dict(self):
        """Convierte el CDR a diccionario"""
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
    """Modelo de llamadas activas extraído del main.py"""
    __tablename__ = "active_calls"
    
    id = Column(Integer, primary_key=True, index=True)
    call_id = Column(String, unique=True, index=True)
    calling_number = Column(String)
    called_number = Column(String)
    direction = Column(String, default="unknown")
    start_time = Column(DateTime, default=datetime.utcnow)
    last_updated = Column(DateTime, default=datetime.utcnow)
    current_duration = Column(Integer, default=0)
    current_cost = Column(Numeric(10,2), default=0)
    zone = Column(String)
    connection_id = Column(String)
    
    def to_dict(self):
        """Convierte la llamada activa a diccionario"""
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