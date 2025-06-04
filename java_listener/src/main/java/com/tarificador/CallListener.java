package com.tarificador;

import javax.telephony.*;
import javax.telephony.events.*;
import javax.telephony.callcontrol.*;
import javax.telephony.callcontrol.events.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import com.fasterxml.jackson.databind.DeserializationFeature;

public class CallListener implements CallControlCallObserver {
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);    
    
    // Almacenar información de las llamadas activas
    private static final Map<String, CallData> activeCalls = new ConcurrentHashMap<>();
    
    // ✅ NUEVOS MAPS PARA TRACKING DE DIRECCIONES
    private static final Map<String, CallDirection> callDirections = new ConcurrentHashMap<>();
    private static final Map<String, String> dialingConnections = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> callConnections = new ConcurrentHashMap<>();
    
    // ✅ ENUM PARA DIRECCIONES
    enum CallDirection {
        INBOUND, OUTBOUND, UNKNOWN
    }
    
    private static class CallData {
        String callId;
        String callingNumber;
        String calledNumber;
        Instant startTime;
        Instant dialingTime; // Cuando empieza a marcar
        Instant networkReachedTime; // Cuando llega a la red del destino
        Instant networkAlertingTime; // Cuando empieza a timbrar en destino
        Instant destinationEstablishedTime; // Cuando el destino contesta
        Instant endTime;
        String status;
        String direction = "unknown";
        int releaseCause = 0;
        boolean hasDialedNumber = false;

        // Nuevo campo para la tarea de reporte periódico
        ScheduledFuture<?> reporterTask;

        // Modificar el constructor para incluir el callId
        CallData(String callId, String callingNumber, String calledNumber) {
            this.callId = callId;  // Asignar el callId desde el constructor
            this.callingNumber = callingNumber;
            this.calledNumber = calledNumber;
            this.startTime = Instant.now();
            this.status = "initiated";
        }
    }

    @Override
    public void callChangedEvent(CallEv[] events) {
        for (CallEv ev : events) {
            Instant eventTime = Instant.now();
            System.out.println(String.format("[%s] Evento: %s ID: %d", 
                eventTime, ev.getClass().getSimpleName(), ev.getID()));
            
            try {
                Call call = ev.getCall();
                String callId = String.valueOf(call.hashCode());
                
                // Debug para eventos de conexión
                if (ev instanceof ConnEv) {
                    ConnEv connEv = (ConnEv) ev;
                    Connection conn = connEv.getConnection();
                    System.out.println("  Conexión: " + conn.getAddress().getName() + 
                                     " Estado: " + getConnectionState(conn));
                }
                
                // Procesar eventos básicos
                if (ev instanceof ConnCreatedEv) {
                    handleConnCreated((ConnCreatedEv) ev, callId);
                } else if (ev instanceof ConnInProgressEv) {
                    handleConnInProgress((ConnInProgressEv) ev, callId);
                } else if (ev instanceof ConnAlertingEv) {
                    handleConnAlerting((ConnAlertingEv) ev, callId);
                } else if (ev instanceof ConnConnectedEv) {
                    handleConnConnected((ConnConnectedEv) ev, callId);
                } else if (ev instanceof ConnDisconnectedEv) {
                    handleConnDisconnected((ConnDisconnectedEv) ev, callId);
                } else if (ev instanceof ConnFailedEv) {
                    handleConnFailed((ConnFailedEv) ev, callId);
                }
                
                // Procesar eventos CallControl
                if (ev instanceof CallCtlConnDialingEv) {
                    handleCallCtlConnDialing((CallCtlConnDialingEv) ev, callId);
                } else if (ev instanceof CallCtlConnNetworkReachedEv) {
                    handleCallCtlConnNetworkReached((CallCtlConnNetworkReachedEv) ev, callId);
                } else if (ev instanceof CallCtlConnNetworkAlertingEv) {
                    handleCallCtlConnNetworkAlerting((CallCtlConnNetworkAlertingEv) ev, callId);
                } else if (ev instanceof CallCtlConnEstablishedEv) {
                    handleCallCtlConnEstablished((CallCtlConnEstablishedEv) ev, callId);
                } else if (ev instanceof CallCtlTermConnRingingEv) {
                    handleCallCtlTermConnRinging((CallCtlTermConnRingingEv) ev, callId);
                } else if (ev instanceof CallCtlTermConnTalkingEv) {
                    handleCallCtlTermConnTalking((CallCtlTermConnTalkingEv) ev, callId);
                } else if (ev instanceof CallCtlConnDisconnectedEv) {
                    handleCallCtlConnDisconnected((CallCtlConnDisconnectedEv) ev, callId);
                }
                
                // Otros eventos
                if (ev instanceof CallActiveEv) {
                    System.out.println("Llamada activa: " + callId);
                } else if (ev instanceof CallInvalidEv) {
                    handleCallInvalid((CallInvalidEv) ev, callId);
                }
                
            } catch (Exception e) {
                System.err.println("Error procesando evento: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    private String getConnectionState(Connection conn) {
        if (conn == null) return "null";
        
        if (conn instanceof CallControlConnection) {
            CallControlConnection ccConn = (CallControlConnection) conn;
            try {
                switch (ccConn.getCallControlState()) {
                    case CallControlConnection.IDLE: return "IDLE";
                    case CallControlConnection.INPROGRESS: return "INPROGRESS";
                    case CallControlConnection.ALERTING: return "ALERTING";
                    case CallControlConnection.DIALING: return "DIALING";
                    case CallControlConnection.ESTABLISHED: return "ESTABLISHED";
                    case CallControlConnection.FAILED: return "FAILED";
                    case CallControlConnection.DISCONNECTED: return "DISCONNECTED";
                    default: return "UNKNOWN_STATE";
                }
            } catch (Exception e) {
                return "ERROR";
            }
        }
        
        return "CONN_" + conn.getState();
    }

    // ✅ CORREGIDO: handleConnCreated mejorado
    private void handleConnCreated(ConnCreatedEv ev, String callId) {
        try {
            Connection conn = ev.getConnection();
            String connectionAddress = conn.getAddress().getName();
            
            // Mantener registro de todas las conexiones
            callConnections.computeIfAbsent(callId, k -> new HashSet<>()).add(connectionAddress);
            Set<String> connections = callConnections.get(callId);
            
            CallData existingCall = activeCalls.get(callId);
            
            if (existingCall != null) {
                // Segunda conexión o más
                if (!connectionAddress.equals(existingCall.callingNumber)) {
                    existingCall.calledNumber = connectionAddress;
                    existingCall.hasDialedNumber = true;
                    
                    System.out.println("  Segunda conexión detectada: " + connectionAddress);
                    
                    // ✅ VERIFICAR SI YA TENEMOS DIRECCIÓN DETERMINADA POR DIALING
                    CallDirection knownDirection = callDirections.get(callId);
                    
                    if (knownDirection == CallDirection.OUTBOUND) {
                        System.out.println("  ✅ Llamada ya identificada como SALIENTE por evento DIALING");
                        existingCall.direction = "outbound";
                        // No cambiar la dirección, ya está correcta
                    } else {
                        System.out.println("  🔍 Esperando eventos ALERTING para determinar dirección...");
                        existingCall.direction = "pending";
                    }
                }
                return;
            }
            
            // Primera conexión
            CallData callData = new CallData(callId, connectionAddress, connectionAddress);
            activeCalls.put(callId, callData);
            
            System.out.println("  Llamada creada - ID: " + callId + 
                            ", Primera conexión: " + callData.callingNumber);
            
        } catch (Exception e) {
            System.err.println("Error en handleConnCreated: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ✅ CORREGIDO: handleCallCtlConnDialing mejorado
    private void handleCallCtlConnDialing(CallCtlConnDialingEv ev, String callId) {
        try {
            Connection conn = ev.getConnection();
            String connection = conn.getAddress().getName();
            
            System.out.println("  MARCANDO: " + callId);
            System.out.println("  Conexión marcando: " + connection);
            
            // ✅ CLAVE: Marcar esta llamada como SALIENTE desde el evento DIALING
            callDirections.put(callId, CallDirection.OUTBOUND);
            dialingConnections.put(callId, connection);
            
            System.out.println("  📞 LLAMADA SALIENTE detectada por DIALING: " + callId);
            
            CallData callData = activeCalls.get(callId);
            if (callData != null) {
                callData.dialingTime = Instant.now();
                callData.status = "dialing";
                callData.direction = "outbound"; // ✅ Marcar como saliente
                
                // ✅ Solo verificar saldo para llamadas SALIENTES
                if (!hasSufficientBalance(callData.callingNumber)) {
                    System.out.println("Saldo insuficiente para " + callData.callingNumber + ". Terminando llamada...");
                    try {
                        CallControlCall ccCall = (CallControlCall) ev.getCall();
                        ccCall.drop();
                    } catch (Exception e) {
                        System.err.println("Error terminando llamada por saldo insuficiente: " + e.getMessage());
                    }
                    return;
                }
            }
        } catch (Exception e) {
            System.err.println("Error en handleCallCtlConnDialing: " + e.getMessage());
        }
    }

    private void handleCallCtlConnDisconnected(CallCtlConnDisconnectedEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            System.out.println("  Desconexión: " + Instant.now());
        }
    }

    private void handleCallCtlConnNetworkReached(CallCtlConnNetworkReachedEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            callData.networkReachedTime = Instant.now();
            System.out.println("  RED ALCANZADA: " + callId);
        }
    }
    
    private void handleCallCtlConnNetworkAlerting(CallCtlConnNetworkAlertingEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            callData.networkAlertingTime = Instant.now();
            callData.status = "ringing";
            System.out.println("  TIMBRANDO EN DESTINO: " + callId);
        }
    }
    
    private void handleCallCtlTermConnRinging(CallCtlTermConnRingingEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            System.out.println("  TERMINAL RINGING: " + callId);
        }
    }

    // ✅ CORREGIDO: handleCallCtlConnEstablished mejorado
    private void handleCallCtlConnEstablished(CallCtlConnEstablishedEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            try {
                Connection conn = ev.getConnection();
                String address = conn.getAddress().getName();
                
                System.out.println("  ESTABLISHED detectado en: " + address);
                
                // ✅ NUEVA LÓGICA: Solo marcar como contestado si es el DESTINO quien establece
                boolean isDestinationAnswering = false;
                
                // Para llamadas entrantes: el destino es la extensión interna
                if ("inbound".equals(callData.direction) || "pending".equals(callData.direction)) {
                    if (isInternalExtension(address)) {
                        isDestinationAnswering = true;
                        System.out.println("  ✅ DESTINO INTERNO CONTESTÓ: " + address);
                    }
                }
                // Para llamadas salientes: el destino es el número externo
                else if ("outbound".equals(callData.direction)) {
                    if (!isInternalExtension(address) && address.equals(callData.calledNumber)) {
                        isDestinationAnswering = true;
                        System.out.println("  ✅ DESTINO EXTERNO CONTESTÓ: " + address);
                    }
                }
                
                // ✅ SOLO establecer destinationEstablishedTime si realmente es el destino contestando
                if (isDestinationAnswering && callData.destinationEstablishedTime == null) {
                    callData.destinationEstablishedTime = Instant.now();
                    callData.status = "answered";  // ✅ Cambiar estado a "answered"
                    System.out.println("  *** DESTINO CONTESTÓ (ESTABLISHED) ***: " + callId + 
                                    " en " + callData.destinationEstablishedTime);
                } else {
                    // ✅ Si no es el destino, solo es establecimiento de origen/red
                    System.out.println("  CONEXIÓN ORIGEN/RED ESTABLECIDA: " + address + " (no es respuesta del destino)");
                    
                    // Si aún no tenemos estado de respuesta, mantener como "ringing" o "alerting"
                    if (callData.destinationEstablishedTime == null) {
                        callData.status = "ringing";
                    }
                }
                
                // ✅ PROGRAMAR REPORTES PERIÓDICOS PARA TODAS LAS LLAMADAS (contestadas o no)
                if (callData.reporterTask == null || callData.reporterTask.isCancelled()) {
                    schedulePeriodicReporting(callId, callData);
                }
                
            } catch (Exception e) {
                System.err.println("Error en handleCallCtlConnEstablished: " + e.getMessage());
            }
        }
    }

    // ✅ NUEVO: Método para programar reportes periódicos
    private void schedulePeriodicReporting(String callId, CallData callData) {
        // ✅ SOLO crear reporterTask si no existe uno ya
        if (callData.reporterTask == null || callData.reporterTask.isCancelled()) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
                try {
                    // ✅ Verificar que la llamada aún existe
                    if (activeCalls.containsKey(callId)) {
                        // ✅ Aplicar último fallback si aún no hay dirección
                        if ("pending".equals(callData.direction) || "unknown".equals(callData.direction)) {
                            applyDirectionFallback(callData);
                        }
                        
                        reportActiveCall(callData);
                    } else {
                        // Si la llamada ya no existe, cancelar el task
                        Thread.currentThread().interrupt();
                        scheduler.shutdown();
                    }
                } catch (Exception e) {
                    System.err.println("Error en reporte periódico para llamada " + callId + ": " + e.getMessage());
                }
            }, 2, 5, TimeUnit.SECONDS); // ✅ Delay inicial de 2 segundos
            
            callData.reporterTask = task;
            System.out.println("✅ Reporting task scheduled successfully for call: " + callId);
        }
    }

    private void handleCallCtlTermConnTalking(CallCtlTermConnTalkingEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            try {
                TerminalConnection termConn = ev.getTerminalConnection();
                String terminalName = termConn.getTerminal().getName();
                
                System.out.println("  TALKING detectado en terminal: " + terminalName);
                System.out.println("  *** DESTINO TALKING ***: " + callId);
            } catch (Exception e) {
                System.err.println("Error en handleCallCtlTermConnTalking: " + e.getMessage());
            }
        }
    }
    
    private void handleConnInProgress(ConnInProgressEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            callData.status = "in_progress";
            System.out.println("  Llamada en progreso: " + callId);
        }
    }

    // ✅ CORREGIDO: handleConnAlerting mejorado
    private void handleConnAlerting(ConnAlertingEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            callData.status = "alerting";
            
            try {
                Connection conn = ev.getConnection();
                String alertingAddress = conn.getAddress().getName();
                
                System.out.println("  🔔 ALERTING detectado en: " + alertingAddress);
                
                // ✅ Si aún no se ha determinado dirección, usar ALERTING para determinarla
                if ("pending".equals(callData.direction) && callData.hasDialedNumber) {
                    determineDirectionFromAlerting(callData, alertingAddress);
                }
                
            } catch (Exception e) {
                System.err.println("Error determinando dirección en ALERTING: " + e.getMessage());
            }
            
            System.out.println("  Alerting: " + callId);
        }
    }

    // ✅ NUEVO: Método para determinar dirección desde ALERTING
    private void determineDirectionFromAlerting(CallData callData, String alertingAddress) {
        String firstAddress = callData.callingNumber;
        String secondAddress = callData.calledNumber;
        
        // El que está en ALERTING es quien RECIBE la llamada
        if (alertingAddress.equals(firstAddress)) {
            // Primer número está en alerting = está recibiendo
            // Segundo → Primero (típicamente INBOUND)
            if (isInternalExtension(firstAddress) && !isInternalExtension(secondAddress)) {
                callData.callingNumber = secondAddress;  // Externo origina
                callData.calledNumber = firstAddress;    // Interno recibe
                callData.direction = "inbound";
                System.out.println("  📱 LLAMADA ENTRANTE CONFIRMADA: " + secondAddress + " → " + firstAddress);
            } else {
                callData.direction = determineDirectionByNumbers(secondAddress, firstAddress);
                System.out.println("  📞 Dirección determinada: " + callData.direction);
            }
            
        } else if (alertingAddress.equals(secondAddress)) {
            // Segundo número está en alerting = está recibiendo
            // Primero → Segundo (típicamente OUTBOUND)
            callData.direction = determineDirectionByNumbers(firstAddress, secondAddress);
            System.out.println("  📞 LLAMADA CONFIRMADA: " + firstAddress + " → " + secondAddress + " [" + callData.direction + "]");
        }
        
        // Verificar saldo solo para llamadas SALIENTES
        if ("outbound".equals(callData.direction)) {
            if (!canMakeCall(callData.callingNumber, callData.calledNumber)) {
                System.out.println("*** SALDO INSUFICIENTE PARA DESTINO ***");
                return;
            }
        }
    }

    // ✅ CORREGIDO: handleConnConnected mejorado
    private void handleConnConnected(ConnConnectedEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            try {
                String addressName = ev.getConnection().getAddress().getName();
                Set<String> connections = callConnections.get(callId);
                CallDirection direction = callDirections.get(callId);
                
                if (!addressName.equals(callData.callingNumber)) {
                    callData.calledNumber = addressName;
                    callData.hasDialedNumber = true;
                }
                
                // ✅ NUEVA LÓGICA: Usar información de dirección ya determinada
                if (connections != null && connections.size() >= 2 && direction != null) {
                    
                    String callingNumber, calledNumber;
                    
                    if (direction == CallDirection.OUTBOUND) {
                        // Para llamadas salientes: el que marcó es calling, el destino es called
                        String originator = dialingConnections.get(callId);
                        String destination = connections.stream()
                            .filter(conn -> !conn.equals(originator))
                            .findFirst()
                            .orElse("unknown");
                        
                        callingNumber = originator;
                        calledNumber = destination;
                        
                        System.out.println("  📞 LLAMADA SALIENTE: " + callingNumber + " → " + calledNumber);
                        
                    } else if (direction == CallDirection.INBOUND) {
                        // Para llamadas entrantes: el externo es calling, el local es called
                        String localExtension = findLocalExtension(connections);
                        String externalNumber = connections.stream()
                            .filter(conn -> !conn.equals(localExtension))
                            .findFirst()
                            .orElse("unknown");
                        
                        callingNumber = externalNumber;
                        calledNumber = localExtension;
                        
                        System.out.println("  📱 LLAMADA ENTRANTE: " + callingNumber + " → " + calledNumber);
                        
                    } else {
                        // Fallback para direcciones unknown
                        System.out.println("  🔄 FALLBACK en ConnConnected: Determinando dirección...");
                        
                        String localExt = findLocalExtension(connections);
                        String otherNumber = connections.stream()
                            .filter(conn -> !conn.equals(localExt))
                            .findFirst()
                            .orElse("unknown");
                        
                        // Si no hay evidencia de DIALING, asumir entrante
                        direction = CallDirection.INBOUND;
                        callDirections.put(callId, direction);
                        
                        callingNumber = otherNumber;
                        calledNumber = localExt;
                        
                        System.out.println("  📱 LLAMADA ENTRANTE (ConnConnected FALLBACK): " + callingNumber + " → " + calledNumber);
                    }
                    
                    // Actualizar información de la llamada
                    callData.callingNumber = callingNumber;
                    callData.calledNumber = calledNumber;
                    callData.direction = direction.toString().toLowerCase();
                }
                
            } catch (Exception e) {
                System.err.println("Error en handleConnConnected: " + e.getMessage());
            }
            
            System.out.println("  Llamada conectada - ID: " + callId + 
                            ", De: " + callData.callingNumber + 
                            ", A: " + callData.calledNumber + 
                            ", Dirección: " + callData.direction);
        }
    }

    // ✅ NUEVO: Método auxiliar para encontrar extensión local
    private String findLocalExtension(Set<String> connections) {
        return connections.stream()
            .filter(this::isInternalExtension)
            .findFirst()
            .orElse(connections.iterator().next());
    }

    // ✅ NUEVO: Método para aplicar fallback de dirección
    private void applyDirectionFallback(CallData callData) {
        if ("pending".equals(callData.direction) || "unknown".equals(callData.direction)) {
            System.out.println("⚠️  Aplicando último fallback para dirección...");
            
            if (isInternalExtension(callData.callingNumber) && !isInternalExtension(callData.calledNumber)) {
                // Intercambiar para llamada entrante
                String temp = callData.callingNumber;
                callData.callingNumber = callData.calledNumber;
                callData.calledNumber = temp;
                callData.direction = "inbound";
            } else {
                callData.direction = determineDirectionByNumbers(callData.callingNumber, callData.calledNumber);
            }
            
            System.out.println("  📞 Dirección final: " + callData.direction);
        }
    }

    private String determineDirectionByNumbers(String callingNumber, String calledNumber) {
        boolean callingIsInternal = isInternalExtension(callingNumber);
        boolean calledIsInternal = isInternalExtension(calledNumber);
        
        if (callingIsInternal && !calledIsInternal) {
            return "outbound";  // Interno → Externo
        } else if (!callingIsInternal && calledIsInternal) {
            return "inbound";   // Externo → Interno
        } else if (callingIsInternal && calledIsInternal) {
            return "internal";  // Interno → Interno
        } else {
            return "transit";   // Externo → Externo
        }
    }

    private boolean isInternalExtension(String number) {
        if (number == null || number.trim().isEmpty()) {
            return false;
        }
        
        String cleanNumber = number.replaceAll("[^0-9]", "");
        
        if (cleanNumber.length() == 4 && cleanNumber.matches("^[3-5].*")) {
            return true;
        }
        
        try {
            int num = Integer.parseInt(cleanNumber);
            return num >= 3000 && num <= 5999;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void handleConnDisconnected(ConnDisconnectedEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            callData.endTime = Instant.now();
            
            // ✅ DETERMINAR ESTADO FINAL CORRECTO
            if (callData.destinationEstablishedTime == null) {
                // No fue contestada
                if ("ringing".equals(callData.status) || "alerting".equals(callData.status)) {
                    callData.status = "no_answer";
                    System.out.println("  ❌ LLAMADA NO CONTESTADA - ID: " + callId);
                } else {
                    callData.status = "failed";
                    System.out.println("  ❌ LLAMADA FALLÓ - ID: " + callId);
                }
            } else {
                // Fue contestada y luego desconectada
                callData.status = "completed";
                System.out.println("  ✅ LLAMADA COMPLETADA - ID: " + callId);
            }
            
            if (ev instanceof CallCtlConnDisconnectedEv) {
                CallCtlConnDisconnectedEv ctlEv = (CallCtlConnDisconnectedEv) ev;
                callData.releaseCause = ctlEv.getCallControlCause();
            }
            
            System.out.println("  Llamada desconectada - ID: " + callId + " Estado final: " + callData.status);

            // ✅ Cancelar task y limpiar recursos
            if (callData.reporterTask != null && !callData.reporterTask.isCancelled()) {
                boolean cancelled = callData.reporterTask.cancel(true);
                System.out.println("  Tarea de reporte periódico cancelada para llamada: " + callId + " (success: " + cancelled + ")");
            }

            // ✅ Limpiar maps de tracking
            callDirections.remove(callId);
            dialingConnections.remove(callId);
            callConnections.remove(callId);

            // ✅ Remover de activeCalls para evitar reportes adicionales
            activeCalls.remove(callId);

            // Mostrar resumen de tiempos
            printCallSummary(callData);
            
            boolean shouldSendCDR = callData.hasDialedNumber || !callData.callingNumber.equals(callData.calledNumber);
            
            if (callData.callingNumber.equals(callData.calledNumber) && !callData.hasDialedNumber) {
                System.out.println("Ignorando llamada: solo se levantó y colgó el auricular");
                shouldSendCDR = false;
            }
            
            if (shouldSendCDR) {
                sendCDR(callData);
            }

            // Reportar eliminación de llamada activa
            reportCallEnd(callId);
        }
    }

    private void printCallSummary(CallData callData) {
        System.out.println("\n=== RESUMEN DE TIEMPOS ===");
        System.out.println("  Start: " + callData.startTime);
        System.out.println("  Dialing: " + callData.dialingTime);
        System.out.println("  Network Reached: " + callData.networkReachedTime);
        System.out.println("  Network Alerting (Ringing): " + callData.networkAlertingTime);
        System.out.println("  Destination Established (Answered): " + callData.destinationEstablishedTime);
        System.out.println("  End: " + callData.endTime);
        
        if (callData.destinationEstablishedTime != null && callData.endTime != null) {
            long billableDuration = callData.endTime.getEpochSecond() - 
                                callData.destinationEstablishedTime.getEpochSecond();
            System.out.println("  DURACIÓN FACTURABLE (desde established): " + billableDuration + " segundos");
        }
        System.out.println("========================\n");
    }

    private void reportCallEnd(String callId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8000/api/active-calls/" + callId))
                    .DELETE()
                    .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("Llamada activa eliminada del monitoreo: " + callId);
            } else {
                System.err.println("Error eliminando llamada activa: " + response.statusCode() + " - " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Error reportando fin de llamada: " + e.getMessage());
        }
    }

    private void handleConnFailed(ConnFailedEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            callData.endTime = Instant.now();
            callData.status = "failed";
            
            if (ev instanceof CallCtlConnFailedEv) {
                CallCtlConnFailedEv ctlEv = (CallCtlConnFailedEv) ev;
                callData.releaseCause = ctlEv.getCallControlCause();
            }
            
            System.out.println("  Llamada fallida - ID: " + callId);

            if (callData.reporterTask != null) {
                callData.reporterTask.cancel(false);
                System.out.println("  Tarea de reporte periódico cancelada para llamada fallida: " + callId);
            }

            // Limpiar maps de tracking
            callDirections.remove(callId);
            dialingConnections.remove(callId);
            callConnections.remove(callId);

            if (callData.hasDialedNumber || !callData.callingNumber.equals(callData.calledNumber)) {
                sendCDR(callData);
            }
            
            activeCalls.remove(callId);
        }
    }
    
    private void handleCallInvalid(CallInvalidEv ev, String callId) {
        // Limpiar todos los datos de la llamada
        activeCalls.remove(callId);
        callDirections.remove(callId);
        dialingConnections.remove(callId);
        callConnections.remove(callId);
    }
    
    private void sendCDR(CallData callData) {
        try {
            System.out.println("\n=== ENVIANDO CDR ===");
            System.out.println("  📞 Llamada: " + callData.callingNumber + " → " + callData.calledNumber);
            System.out.println("  📍 Dirección: " + callData.direction);
            System.out.println("  ⏱️  Duración total: " + 
                (callData.endTime.getEpochSecond() - callData.startTime.getEpochSecond()) + " segundos");
            
            if (callData.destinationEstablishedTime != null) {
                long billable = callData.endTime.getEpochSecond() - callData.destinationEstablishedTime.getEpochSecond();
                System.out.println("  💰 Duración facturable: " + billable + " segundos");
            }
            System.out.println("====================");
            
            Map<String, Object> cdr = new HashMap<>();
            cdr.put("calling_number", callData.callingNumber);
            cdr.put("called_number", callData.calledNumber);
            cdr.put("start_time", callData.startTime.toString());
            cdr.put("dialing_time", callData.dialingTime != null ? callData.dialingTime.toString() : null);
            cdr.put("network_reached_time", callData.networkReachedTime != null ? 
                    callData.networkReachedTime.toString() : null);
            cdr.put("network_alerting_time", callData.networkAlertingTime != null ? 
                    callData.networkAlertingTime.toString() : null);
            cdr.put("answer_time", callData.destinationEstablishedTime != null ? 
                    callData.destinationEstablishedTime.toString() : null);
            cdr.put("end_time", callData.endTime.toString());
            cdr.put("status", callData.status);
            cdr.put("direction", callData.direction);
            cdr.put("release_cause", callData.releaseCause);
            
            // Calcular duración total
            long durationTotal = 0;
            if (callData.startTime != null && callData.endTime != null) {
                durationTotal = callData.endTime.getEpochSecond() - callData.startTime.getEpochSecond();
            }
            cdr.put("duration_seconds", durationTotal);
            
            // Calcular duración facturable
            long durationBillable = 0;
            if (callData.destinationEstablishedTime != null && callData.endTime != null) {
                durationBillable = callData.endTime.getEpochSecond() - 
                                callData.destinationEstablishedTime.getEpochSecond();
            }
            cdr.put("duration_billable", durationBillable);
            
            String json = objectMapper.writeValueAsString(cdr);
            System.out.println("JSON CDR: " + json);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8000/cdr"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("CDR enviado. Respuesta: " + response.statusCode());
            
            if (response.statusCode() >= 400) {
                System.err.println("Error en respuesta CDR: " + response.body());
            }
            
        } catch (Exception e) {
            System.err.println("Error enviando CDR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean hasSufficientBalance(String callingNumber) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8000/check_balance/" + callingNumber))
                    .timeout(Duration.ofSeconds(5)) 
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            return response.body().contains("true");
        } catch (Exception e) {
            System.err.println("Error verificando saldo: " + e.getMessage());
            return false;
        }
    }

    private static class BalanceCheckResponse {
        public boolean can_call;
        public String reason;
        public double balance;
        public String zona;
        public double tarifa_segundo;
        public int tiempo_disponible_segundos;
        
        public BalanceCheckResponse() {}
    }

    private boolean canMakeCall(String callingNumber, String calledNumber) {
        try {
            String url = String.format("http://localhost:8000/check_balance_for_call/%s/%s", 
                                    callingNumber, calledNumber);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            BalanceCheckResponse result = objectMapper.readValue(response.body(), BalanceCheckResponse.class);
            
            if (!result.can_call) {
                System.out.printf("  ❌ NO PUEDE LLAMAR - Saldo: $%.5f, Zona: %s, Tarifa/seg: $%.5f, Razón: %s%n",
                    result.balance,
                    result.zona != null ? result.zona : "N/A",
                    result.tarifa_segundo,
                    result.reason != null ? result.reason : "Unknown"
                );
            } else {
                int minutos = result.tiempo_disponible_segundos / 60;
                int segundos = result.tiempo_disponible_segundos % 60;
                System.out.printf("  ✅ PUEDE LLAMAR - Saldo: $%.5f, Zona: %s, Tarifa/seg: $%.5f, Tiempo disponible: %d:%02d%n",
                    result.balance,
                    result.zona != null ? result.zona : "N/A",
                    result.tarifa_segundo,
                    minutos, segundos
                );
            }
            
            return result.can_call;
        } catch (Exception e) {
            System.err.println("Error verificando capacidad de llamada: " + e.getMessage());
            return false;
        }
    }

    // ✅ CORREGIDO: reportActiveCall mejorado
    private void reportActiveCall(CallData callData) {
        try {
            if (!activeCalls.containsKey(callData.callId)) {
                System.out.println("⚠️  Llamada " + callData.callId + " ya no existe, cancelando reporte");
                return;
            }

            if ("disconnected".equals(callData.status) || callData.endTime != null) {
                System.out.println("⚠️  Llamada " + callData.callId + " ya terminó, cancelando reporte");
                return;
            }

            Map<String, Object> activeCall = new HashMap<>();
            activeCall.put("call_id", callData.callId);
            activeCall.put("calling_number", callData.callingNumber);
            activeCall.put("called_number", callData.calledNumber);
            activeCall.put("direction", callData.direction);
            activeCall.put("start_time", callData.startTime.toString());

            long durationSeconds = 0;
            if (callData.destinationEstablishedTime != null) {
                durationSeconds = Instant.now().getEpochSecond() - 
                                callData.destinationEstablishedTime.getEpochSecond();
            }
            activeCall.put("current_duration", durationSeconds);

            double tarifaSegundo = 0.0;
            String zona = "Desconocida";
            
            if ("outbound".equals(callData.direction)) {
                try {
                    String url = String.format("http://localhost:8000/check_balance_for_call/%s/%s", 
                                            callData.callingNumber, callData.calledNumber);
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    BalanceCheckResponse result = objectMapper.readValue(response.body(), BalanceCheckResponse.class);
                    
                    tarifaSegundo = result.tarifa_segundo;
                    zona = result.zona;
                } catch (Exception e) {
                    System.err.println("Error obteniendo tarifa: " + e.getMessage());
                }
            } else {
                zona = "Entrante";
            }
            
            double costoEstimado = durationSeconds * tarifaSegundo;
            activeCall.put("current_cost", costoEstimado);
            activeCall.put("zone", zona);
            activeCall.put("connection_id", callData.callId);
            
            System.out.println("Enviando reporte de llamada activa: " + callData.callingNumber + 
                            " -> " + callData.calledNumber + 
                            " (" + callData.direction + ") " +
                            "(dur: " + durationSeconds + "s, costo: $" + 
                            String.format("%.2f", costoEstimado) + ")");
            
            String json = objectMapper.writeValueAsString(activeCall);
            HttpRequest reportRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8000/api/active-calls"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
                    
            HttpResponse<String> reportResponse = client.send(reportRequest, HttpResponse.BodyHandlers.ofString());
            
            if (reportResponse.statusCode() >= 400) {
                System.err.println("Error reportando llamada activa: " + reportResponse.statusCode() + 
                                " - " + reportResponse.body());
            }
        
        } catch (Exception e) {
            System.err.println("Error reporting active call: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Caches para evitar llamadas HTTP repetidas
    private static final Map<String, Double> tarifaCache = new ConcurrentHashMap<>();
    private static final Map<String, String> zonaCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> tarifaCacheTime = new ConcurrentHashMap<>();
}