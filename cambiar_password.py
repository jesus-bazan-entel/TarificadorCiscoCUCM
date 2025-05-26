from passlib.context import CryptContext
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from datetime import datetime

# Configuración
DATABASE_URL = "postgresql://tarificador_user:fr4v4t3l@localhost/tarificador"
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

# Conexión a la base de datos
engine = create_engine(DATABASE_URL)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

# Definir el modelo Usuario
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy import Column, Integer, String, Boolean, DateTime

Base = declarative_base()

class Usuario(Base):
    __tablename__ = "usuarios"
    id = Column(Integer, primary_key=True, index=True)
    username = Column(String, unique=True, index=True)
    password = Column(String)
    nombre = Column(String)
    apellido = Column(String)
    email = Column(String, nullable=True)
    role = Column(String)
    activo = Column(Boolean, default=True)
    ultimo_login = Column(DateTime, nullable=True)

def cambiar_password(username: str, nueva_password: str):
    db = SessionLocal()
    usuario = db.query(Usuario).filter(Usuario.username == username).first()
    if usuario:
        usuario.password = pwd_context.hash(nueva_password)
        usuario.ultimo_login = datetime.utcnow()
        db.commit()
        print(f"Contraseña actualizada para el usuario '{username}'.")
    else:
        print(f"Usuario '{username}' no encontrado.")
    db.close()

if __name__ == "__main__":
    import sys
    if len(sys.argv) != 3:
        print("Uso: python cambiar_password.py <usuario> <nueva_contraseña>")
    else:
        cambiar_password(sys.argv[1], sys.argv[2])
