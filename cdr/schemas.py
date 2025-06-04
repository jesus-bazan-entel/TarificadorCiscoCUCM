# cdr/schemas.py
from pydantic import BaseModel, field_validator, Field
from typing import Optional, Any, List
from datetime import datetime

class CallEvent(BaseModel):
    """Schema para eventos de llamada - extraído del main.py"""
    calling_number: str
    called_number: str
    start_time: Any
    end_time: Any
    duration_seconds: int
    duration_billable: int
    status: str
    direction: Optional[str] = "unknown"
    release_cause: Optional[int] = 0
    answer_time: Optional[Any] = None
    dialing_time: Optional[Any] = None
    network_reached_time: Optional[Any] = None
    network_alerting_time: Optional[Any] = None

    @field_validator('start_time', 'end_time', 'answer_time', 'dialing_time', 
                    'network_reached_time', 'network_alerting_time', mode='before')
    @classmethod
    def convert_datetime_fields(cls, v):
        """Convierte cualquier formato de fecha a datetime"""
        if v is None or v == "null" or v == "":
            return None
        
        if isinstance(v, datetime):
            return v
        
        if isinstance(v, str):
            try:
                if 'T' in v and v.endswith('Z'):
                    return datetime.fromisoformat(v.replace('Z', '+00:00'))
                elif 'T' in v:
                    return datetime.fromisoformat(v)
                else:
                    return datetime.strptime(v, '%Y-%m-%d %H:%M:%S')
            except:
                return datetime.now()
        
        return datetime.now()

    model_config = {
        "extra": "ignore",
        "str_strip_whitespace": True
    }

class ActiveCallRequest(BaseModel):
    """Schema para reportar llamadas activas"""
    call_id: str
    calling_number: str
    called_number: str
    direction: Optional[str] = "unknown"
    zone: Optional[str] = "Desconocida"
    start_time: str
    current_duration: int = 0
    current_cost: float = 0.0
    connection_id: Optional[str] = None

class CDRFilter(BaseModel):
    """Schema para filtros de consulta CDR"""
    phone_number: Optional[str] = None
    calling_number: Optional[str] = None
    called_number: Optional[str] = None
    start_date: Optional[str] = None
    end_date: Optional[str] = None
    min_duration: Optional[int] = 0
    status: Optional[str] = None
    direction: Optional[str] = None
    page: int = Field(1, ge=1)
    per_page: int = Field(10, ge=1, le=100)

class CDRResponse(BaseModel):
    """Schema para respuesta CDR"""
    id: int
    calling_number: str
    called_number: str
    start_time: Optional[datetime]
    end_time: Optional[datetime]
    duration_seconds: int
    duration_billable: int
    cost: float
    status: str
    direction: str
    zone_name: Optional[str] = None
    call_type: Optional[str] = None
    direction_display: Optional[str] = None
    
    class Config:
        from_attributes = True

class CDRStats(BaseModel):
    """Schema para estadísticas de CDR"""
    total_calls: int = 0
    completed_calls: int = 0
    failed_calls: int = 0
    unanswered_calls: int = 0
    total_cost: float = 0.0
    total_duration: int = 0

class CDRListResponse(BaseModel):
    """Schema para respuesta de lista de CDR"""
    records: List[CDRResponse]
    total_records: int
    total_pages: int
    current_page: int
    stats: CDRStats
    filters_applied: CDRFilter